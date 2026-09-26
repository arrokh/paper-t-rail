package com.papertrail.api.scholarly.references.repository

import com.fasterxml.jackson.databind.ObjectMapper
import com.papertrail.api.scholarly.references.client.ScholarlyWork
import com.papertrail.api.scholarly.references.model.StoredBibliographyReference
import com.papertrail.api.scholarly.references.report.BibliographyResolutionReportEntry
import com.papertrail.api.scholarly.references.report.ReportCanonicalPaper
import com.papertrail.api.scholarly.references.resolver.ReferenceResolutionDecision
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
    private val objectMapper: ObjectMapper,
) {
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
                match_method, provider_id
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?)
            ON CONFLICT (analysis_run_id, bibliography_entry_id) DO NOTHING
            """.trimIndent(),
            analysisRunId,
            reference.id,
            decision.status.name,
            decision.reasonCode,
            canonicalPaperId,
            decision.work?.doi?.let(DoiNormalizer::normalize),
            decision.work?.title,
            objectMapper.writeValueAsString(decision.work?.authors.orEmpty()),
            decision.work?.year,
            decision.score,
            decision.matchMethod,
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
               r.match_method
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
            objectMapper.writeValueAsString(work.authors),
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

    private fun ResultSet.toStoredReference() = StoredBibliographyReference(
        id = getObject("id", UUID::class.java),
        entryOrder = getInt("entry_order"),
        localReferenceKey = getString("local_reference_key"),
        rawText = getString("raw_text"),
        title = getString("parsed_title"),
        authors = objectMapper.readValue(getString("parsed_authors"), objectMapper.typeFactory.constructCollectionType(List::class.java, String::class.java)),
        year = getObject("parsed_year", Integer::class.java)?.toInt(),
        doi = getString("parsed_doi"),
        referenceType = getString("reference_type"),
    )

    private fun ResultSet.toReportEntry(): BibliographyResolutionReportEntry {
        val status = getString("status")
        val canonicalPaperId = getObject("canonical_paper_id", UUID::class.java)
        val candidate = getString("matched_title")?.let { title ->
            ReportCanonicalPaper(
                id = requireNotNull(canonicalPaperId) { "Resolved Bibliography Entry is missing its Canonical Paper identity." },
                doi = getString("matched_doi"),
                title = title,
                authors = objectMapper.readValue(getString("matched_authors"), objectMapper.typeFactory.constructCollectionType(List::class.java, String::class.java)),
                year = getObject("matched_year", Integer::class.java)?.toInt(),
            )
        }
        return BibliographyResolutionReportEntry(
            entryOrder = getInt("entry_order"),
            localReferenceKey = getString("local_reference_key"),
            rawText = getString("raw_text"),
            title = getString("parsed_title"),
            authors = objectMapper.readValue(getString("parsed_authors"), objectMapper.typeFactory.constructCollectionType(List::class.java, String::class.java)),
            year = getObject("parsed_year", Integer::class.java)?.toInt(),
            doi = getString("parsed_doi"),
            referenceType = getString("reference_type"),
            status = status ?: "NOT_ATTEMPTED",
            reasonCode = getString("reason_code"),
            canonicalPaper = candidate,
            confidenceScore = getObject("confidence_score", Double::class.javaObjectType)?.toDouble(),
            matchMethod = getString("match_method"),
        )
    }
}
