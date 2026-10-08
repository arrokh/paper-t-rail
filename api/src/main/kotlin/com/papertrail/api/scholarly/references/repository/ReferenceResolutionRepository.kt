package com.papertrail.api.scholarly.references.repository

import com.papertrail.api.utils.JsonUtil
import com.papertrail.api.analysis.configuration.AnalysisConfigurationSnapshot
import com.papertrail.api.citation.parsing.BibliographyNormalizationPolicySelection
import com.papertrail.api.citation.parsing.ParsedBibliographyIdentifier
import com.papertrail.api.citation.parsing.ParsedBibliographySourceLocation
import com.papertrail.api.scholarly.references.client.ScholarlyWork
import com.papertrail.api.scholarly.references.model.StoredBibliographyReference
import com.papertrail.api.scholarly.references.report.BibliographyResolutionReportEntry
import com.papertrail.api.scholarly.references.report.ReportCanonicalPaper
import com.papertrail.api.scholarly.references.resolver.ReferenceResolutionDecision
import com.papertrail.api.scholarly.references.resolver.ScholarlyCandidateEvidence
import com.papertrail.api.scholarly.references.normalization.DoiNormalizer
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.sql.ResultSet
import java.util.UUID

@Repository
class ReferenceResolutionRepository(
    private val jdbc: JdbcTemplate,
) {
    fun loadRun(analysisRunId: UUID): RunResolutionContext? = jdbc.query(
        """
        SELECT run.status,
               run.configuration_snapshot::text AS configuration,
               analysis_run_has_conflict_aware_evidence_coverage(run.configuration_snapshot) AS semantic_pipeline_configured,
               parsed.bibliography_normalization_policy_id,
               parsed.bibliography_normalization_policy_version
          FROM analysis_runs run
          JOIN source_documents document ON document.id = run.document_id
          LEFT JOIN parsed_document_parses parsed ON parsed.analysis_run_id = run.id
         WHERE run.id = ?
           AND NOT EXISTS (
               SELECT 1 FROM source_document_tombstones tombstone WHERE tombstone.document_id = document.id
           )
        """.trimIndent(),
        { rs, _ ->
            RunResolutionContext(
                runStatus = rs.getString("status"),
                configuration = JsonUtil.fromJson(rs.getString("configuration"), AnalysisConfigurationSnapshot::class.java),
                semanticPipelineConfigured = rs.getBoolean("semantic_pipeline_configured"),
                bibliographyNormalizationPolicy = rs.getString("bibliography_normalization_policy_id")?.let { policyId ->
                    rs.getString("bibliography_normalization_policy_version")?.let { version ->
                        BibliographyNormalizationPolicySelection(policyId, version)
                    }
                },
            )
        },
        analysisRunId,
    ).firstOrNull()

    fun pendingEntry(analysisRunId: UUID, bibliographyEntryId: UUID): StoredBibliographyReference? = jdbc.query(
        """
        SELECT b.id, b.entry_order, b.local_reference_key, b.raw_text, b.parsed_title,
               b.parsed_authors::text AS parsed_authors, b.parsed_year, b.parsed_doi, b.reference_type
          FROM bibliography_entries b
          LEFT JOIN bibliography_entry_resolutions r
            ON r.analysis_run_id = b.analysis_run_id AND r.bibliography_entry_id = b.id
         WHERE b.analysis_run_id = ? AND b.id = ? AND r.bibliography_entry_id IS NULL
        """.trimIndent(),
        { rs, _ -> rs.toStoredReference() },
        analysisRunId,
        bibliographyEntryId,
    ).firstOrNull()

    fun isResolved(analysisRunId: UUID, bibliographyEntryId: UUID): Boolean = jdbc.queryForObject(
        "SELECT EXISTS (SELECT 1 FROM bibliography_entry_resolutions WHERE analysis_run_id = ? AND bibliography_entry_id = ? AND status = 'RESOLVED')",
        Boolean::class.java,
        analysisRunId,
        bibliographyEntryId,
    ) == true

    fun resolutionStatus(analysisRunId: UUID, bibliographyEntryId: UUID): String? = jdbc.query(
        "SELECT status FROM bibliography_entry_resolutions WHERE analysis_run_id = ? AND bibliography_entry_id = ?",
        { rs, _ -> rs.getString("status") },
        analysisRunId,
        bibliographyEntryId,
    ).firstOrNull()

    fun hasOutboxRequest(analysisRunId: UUID, eventType: String, bibliographyEntryId: UUID): Boolean = jdbc.queryForObject(
        "SELECT EXISTS (SELECT 1 FROM outbox_events WHERE analysis_run_id = ? AND event_type = ? AND payload -> 'payload' ->> 'bibliographyEntryId' = ?)",
        Boolean::class.java,
        analysisRunId,
        eventType,
        bibliographyEntryId.toString(),
    ) == true

    fun entryExists(analysisRunId: UUID, bibliographyEntryId: UUID): Boolean = jdbc.queryForObject(
        "SELECT EXISTS (SELECT 1 FROM bibliography_entries WHERE analysis_run_id = ? AND id = ?)",
        Boolean::class.java,
        analysisRunId,
        bibliographyEntryId,
    ) == true

    fun resolutionExists(analysisRunId: UUID, bibliographyEntryId: UUID): Boolean = jdbc.queryForObject(
        "SELECT EXISTS (SELECT 1 FROM bibliography_entry_resolutions WHERE analysis_run_id = ? AND bibliography_entry_id = ?)",
        Boolean::class.java,
        analysisRunId,
        bibliographyEntryId,
    ) == true

    fun save(
        analysisRunId: UUID,
        reference: StoredBibliographyReference,
        decision: ReferenceResolutionDecision,
        providerId: String,
    ): UUID? {
        val canonicalPaperId = decision.work?.let { work -> saveCanonicalPaper(work, providerId) }
        jdbc.update(
            """
            INSERT INTO bibliography_entry_resolutions (
                analysis_run_id, bibliography_entry_id, status, reason_code, canonical_paper_id,
                matched_doi, matched_title, matched_authors, matched_year, confidence_score,
                match_method, candidate_evidence, provider_id
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?::jsonb, ?)
            ON CONFLICT (analysis_run_id, bibliography_entry_id) DO NOTHING
            """.trimIndent(),
            analysisRunId,
            reference.id,
            decision.status.name,
            decision.reasonCode,
            canonicalPaperId,
            decision.work?.doi?.let(DoiNormalizer::normalize),
            decision.work?.title,
            JsonUtil.toJson(decision.work?.authors.orEmpty()),
            decision.work?.year,
            decision.score,
            decision.matchMethod,
            JsonUtil.toJson(decision.candidateEvidence),
            providerId,
        )
        return canonicalPaperId
    }

    fun reportEntries(analysisRunId: UUID): List<BibliographyResolutionReportEntry> = jdbc.query(
        """
        SELECT b.entry_order, b.local_reference_key, b.raw_text, b.parsed_title,
               b.parsed_authors::text AS parsed_authors, b.parsed_year, b.parsed_doi, b.reference_type,
               CASE
                   WHEN r.bibliography_entry_id IS NOT NULL THEN r.status
                   WHEN task.event_id IS NOT NULL AND task_inbox.event_id IS NOT NULL THEN 'RESOLUTION_FAILED'
                   ELSE 'NOT_ATTEMPTED'
               END AS status,
               CASE
                   WHEN r.bibliography_entry_id IS NOT NULL THEN r.reason_code
                   WHEN task.event_id IS NOT NULL AND task_inbox.event_id IS NOT NULL THEN 'RESOLUTION_RETRIES_EXHAUSTED'
                   ELSE NULL
               END AS reason_code,
               r.canonical_paper_id, r.matched_doi, r.matched_title,
               r.matched_authors::text AS matched_authors, r.matched_year, r.confidence_score,
               r.match_method, r.candidate_evidence::text AS candidate_evidence,
               access_progress.status AS access_progress_status,
               access_progress.reason_code AS access_progress_reason,
               b.source_element, b.source_text_content, b.source_local_reference_key, b.local_reference_key_origin,
               b.identifiers::text AS identifiers, b.source_locations::text AS source_locations,
               b.provisional_artifact_signals::text AS provisional_artifact_signals,
               b.extraction_limitations::text AS extraction_limitations, b.provenance_capture_status
          FROM bibliography_entries b
          LEFT JOIN bibliography_entry_resolutions r
            ON r.analysis_run_id = b.analysis_run_id AND r.bibliography_entry_id = b.id
          -- A terminal request without a persisted domain outcome is an exhausted processing task, not a domain rejection.
          LEFT JOIN LATERAL (
              SELECT event.event_id
                FROM outbox_events event
               WHERE event.analysis_run_id = b.analysis_run_id
                 AND event.event_type = 'ReferenceResolutionRequested'
                 AND event.payload -> 'payload' ->> 'bibliographyEntryId' = b.id::text
               ORDER BY event.created_at DESC, event.event_id
               LIMIT 1
          ) task ON true
          LEFT JOIN inbox_events task_inbox ON task_inbox.event_id = task.event_id
          LEFT JOIN analysis_run_pipeline_items access_progress
            ON access_progress.analysis_run_id = b.analysis_run_id
           AND access_progress.stage_id = 'access'
           AND access_progress.step_id = 'acquire-source'
           AND access_progress.item_id = b.id::text
         WHERE b.analysis_run_id = ?
         ORDER BY b.entry_order
        """.trimIndent(),
        { rs, _ -> rs.toReportEntry() },
        analysisRunId,
    )

    private fun saveCanonicalPaper(work: ScholarlyWork, providerId: String): UUID {
        val doi = DoiNormalizer.normalize(work.doi)
        val identityKey = doi?.let { "doi:$it" } ?: metadataIdentityKey(work)
        return jdbc.queryForObject(
            """
            INSERT INTO canonical_papers (
                id, identity_key, doi, title, authors, publication_year, metadata_provider_id
            ) VALUES (?, ?, ?, ?, ?::jsonb, ?, ?)
            ON CONFLICT (identity_key) DO UPDATE SET identity_key = canonical_papers.identity_key
            RETURNING id
            """.trimIndent(),
            UUID::class.java,
            UUID.randomUUID(),
            identityKey,
            doi,
            work.title,
            JsonUtil.toJson(work.authors),
            work.year,
            providerId,
        ) ?: error("Canonical Paper identity could not be persisted.")
    }

    private fun metadataIdentityKey(work: ScholarlyWork): String {
        val canonicalMetadata = listOf(
            work.title.trim().lowercase(),
            work.authors.map { it.trim().lowercase() }.sorted().joinToString("|"),
            work.year?.toString().orEmpty(),
        ).joinToString("\u0000")
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(canonicalMetadata.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return "metadata:$digest"
    }

    data class RunResolutionContext(
        val runStatus: String,
        val configuration: AnalysisConfigurationSnapshot,
        val semanticPipelineConfigured: Boolean,
        val bibliographyNormalizationPolicy: BibliographyNormalizationPolicySelection?,
    )

    private fun ResultSet.toStoredReference() = StoredBibliographyReference(
        id = getObject("id", UUID::class.java),
        entryOrder = getInt("entry_order"),
        localReferenceKey = getString("local_reference_key"),
        rawText = getString("raw_text"),
        title = getString("parsed_title"),
        authors = JsonUtil.fromJson(getString("parsed_authors"), JsonUtil.collectionType(List::class.java, String::class.java)),
        year = getObject("parsed_year", Integer::class.java)?.toInt(),
        doi = getString("parsed_doi"),
        referenceType = getString("reference_type"),
    )

    private fun listOfNotCapturedLimitation(provenanceCaptureStatus: String?): List<String> =
        if (provenanceCaptureStatus == "CAPTURED") emptyList() else listOf("BIBLIOGRAPHY_PROVENANCE_UNAVAILABLE")

    private fun ResultSet.toReportEntry(): BibliographyResolutionReportEntry {
        val status = getString("status")
        val canonicalPaperId = getObject("canonical_paper_id", UUID::class.java)
        val candidate = getString("matched_title")?.let { title ->
            ReportCanonicalPaper(
                id = requireNotNull(canonicalPaperId) { "Resolved Bibliography Entry is missing its Canonical Paper identity." },
                doi = getString("matched_doi"),
                title = title,
                authors = JsonUtil.fromJson(getString("matched_authors"), JsonUtil.collectionType(List::class.java, String::class.java)),
                year = getObject("matched_year", Integer::class.java)?.toInt(),
            )
        }
        return BibliographyResolutionReportEntry(
            entryOrder = getInt("entry_order"),
            localReferenceKey = getString("local_reference_key"),
            rawText = getString("raw_text"),
            title = getString("parsed_title"),
            authors = JsonUtil.fromJson(getString("parsed_authors"), JsonUtil.collectionType(List::class.java, String::class.java)),
            year = getObject("parsed_year", Integer::class.java)?.toInt(),
            doi = getString("parsed_doi"),
            referenceType = getString("reference_type"),
            status = status ?: "NOT_ATTEMPTED",
            reasonCode = getString("reason_code"),
            canonicalPaper = candidate,
            confidenceScore = getObject("confidence_score", Double::class.javaObjectType)?.toDouble(),
            matchMethod = getString("match_method"),
            candidateEvidence = getString("candidate_evidence")?.let {
                JsonUtil.fromJson<List<ScholarlyCandidateEvidence>>(it, JsonUtil.collectionType(List::class.java, ScholarlyCandidateEvidence::class.java))
            }.orEmpty(),
            accessProgressStatus = getString("access_progress_status"),
            accessProgressReason = getString("access_progress_reason"),
            sourceTextContent = getString("source_text_content"),
            sourceElement = getString("source_element"),
            sourceLocalReferenceKey = getString("source_local_reference_key"),
            localReferenceKeyOrigin = getString("local_reference_key_origin") ?: "UNKNOWN",
            identifiers = getString("identifiers")?.let {
                JsonUtil.fromJson<List<ParsedBibliographyIdentifier>>(it, JsonUtil.collectionType(List::class.java, ParsedBibliographyIdentifier::class.java))
            }.orEmpty(),
            sourceLocations = getString("source_locations")?.let {
                JsonUtil.fromJson<List<ParsedBibliographySourceLocation>>(it, JsonUtil.collectionType(List::class.java, ParsedBibliographySourceLocation::class.java))
            }.orEmpty(),
            provisionalArtifactSignals = getString("provisional_artifact_signals")?.let {
                JsonUtil.fromJson<List<String>>(it, JsonUtil.collectionType(List::class.java, String::class.java))
            }.orEmpty(),
            extractionLimitations = getString("extraction_limitations")?.let {
                JsonUtil.fromJson<List<String>>(it, JsonUtil.collectionType(List::class.java, String::class.java))
            } ?: listOfNotCapturedLimitation(getString("provenance_capture_status")),
            provenanceCaptureStatus = getString("provenance_capture_status") ?: "UNAVAILABLE",
        )
    }
}
