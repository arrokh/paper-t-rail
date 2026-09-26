package com.papertrail.api.scholarly.acquisition.repository

import com.fasterxml.jackson.databind.ObjectMapper
import com.papertrail.api.scholarly.acquisition.domain.AcquiredFullText
import com.papertrail.api.scholarly.acquisition.domain.CitedPaperAccessDecision
import com.papertrail.api.scholarly.acquisition.domain.CitedPaperAccessReason
import com.papertrail.api.scholarly.acquisition.domain.OpenAccessDiscovery
import com.papertrail.api.scholarly.acquisition.report.CitedPaperAccessReport
import com.papertrail.api.scholarly.acquisition.report.CitedReferenceVerificationOutcome
import com.papertrail.api.infrastructure.crypto.sha256Hex
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.support.TransactionTemplate
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@Repository
class CitedPaperAccessRepository(
    private val jdbc: JdbcTemplate,
    private val objectMapper: ObjectMapper,
    private val transactionTemplate: TransactionTemplate,
) {
    fun accessExists(analysisRunId: UUID, bibliographyEntryId: UUID): Boolean = jdbc.queryForObject(
        "SELECT EXISTS (SELECT 1 FROM cited_paper_access WHERE analysis_run_id = ? AND bibliography_entry_id = ?)",
        Boolean::class.java,
        analysisRunId,
        bibliographyEntryId,
    ) == true

    fun resolvedReference(analysisRunId: UUID, bibliographyEntryId: UUID): ResolvedCitedReference? = jdbc.query(
        """
        SELECT b.id, b.parsed_title, b.parsed_authors::text AS parsed_authors, b.parsed_year, b.parsed_doi, b.reference_type,
               p.id AS canonical_paper_id, p.doi AS canonical_doi
          FROM bibliography_entry_resolutions r
          JOIN bibliography_entries b
            ON b.analysis_run_id = r.analysis_run_id AND b.id = r.bibliography_entry_id
          JOIN canonical_papers p ON p.id = r.canonical_paper_id
         WHERE r.analysis_run_id = ? AND r.bibliography_entry_id = ? AND r.status = 'RESOLVED'
        """.trimIndent(),
        { rs, _ ->
            ResolvedCitedReference(
                bibliographyEntryId = rs.getObject("id", UUID::class.java),
                title = rs.getString("parsed_title"),
                authors = objectMapper.readValue(
                    rs.getString("parsed_authors"),
                    objectMapper.typeFactory.constructCollectionType(List::class.java, String::class.java),
                ),
                year = rs.getObject("parsed_year", Integer::class.java)?.toInt(),
                doi = rs.getString("parsed_doi") ?: rs.getString("canonical_doi"),
                referenceType = rs.getString("reference_type"),
                canonicalPaperId = rs.getObject("canonical_paper_id", UUID::class.java),
            )
        },
        analysisRunId,
        bibliographyEntryId,
    ).firstOrNull()

    fun save(
        analysisRunId: UUID,
        reference: ResolvedCitedReference,
        discovery: OpenAccessDiscovery?,
        decision: CitedPaperAccessDecision,
        accessReason: CitedPaperAccessReason?,
        locationUrl: String?,
        license: String?,
        version: String?,
        hostType: String?,
        providerId: String,
        discoveredAt: Instant,
        objectKey: String?,
        fullText: AcquiredFullText?,
        language: String?,
        languageDetectorVersion: String?,
    ): Boolean = transactionTemplate.execute {
            val contentHash = fullText?.bytes?.let(::sha256Hex)
            val inserted = jdbc.update(
                """
                INSERT INTO cited_paper_access (
                    analysis_run_id, bibliography_entry_id, canonical_paper_id, access_status,
                    provider_id, access_reason, metadata_available, abstract_available, source_url, license_identifier,
                    location_version, location_host_type, discovered_at, object_key, content_sha256,
                    language, language_detector_version
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (analysis_run_id, bibliography_entry_id) DO NOTHING
                """.trimIndent(),
                analysisRunId,
                reference.bibliographyEntryId,
                reference.canonicalPaperId,
                decision.accessStatus.name,
                providerId,
                accessReason?.name,
                discovery?.metadataAvailable == true || discovery?.abstractAvailable == true || discovery?.locations?.isNotEmpty() == true || fullText != null,
                discovery?.abstractAvailable == true,
                locationUrl,
                license,
                version,
                hostType,
                Timestamp.from(discoveredAt),
                objectKey,
                contentHash,
                language,
                languageDetectorVersion,
            )
            if (inserted == 0) return@execute false
            val finalStatus = decision.finalVerificationStatus?.name
            jdbc.update(
                """
                INSERT INTO claim_paper_verifications (
                    id, analysis_run_id, atomic_claim_id, bibliography_entry_id, canonical_paper_id,
                    processing_status, verification_scope, terminal_reason, final_status
                )
                SELECT gen_random_uuid(), ?, claims.atomic_claim_id, ?, ?, ?, ?, ?, ?
                  FROM (
                      SELECT DISTINCT link.atomic_claim_id
                        FROM atomic_claim_citation_targets link
                        JOIN citation_targets target
                          ON target.analysis_run_id = link.analysis_run_id
                         AND target.id = link.citation_target_id
                       WHERE target.analysis_run_id = ? AND target.bibliography_entry_id = ?
                  ) claims
                ON CONFLICT (analysis_run_id, atomic_claim_id, bibliography_entry_id) DO NOTHING
                """.trimIndent(),
                analysisRunId,
                reference.bibliographyEntryId,
                reference.canonicalPaperId,
                if (finalStatus == null) "PENDING" else "COMPLETED",
                decision.verificationScope.name,
                decision.terminalReason,
                finalStatus,
                analysisRunId,
                reference.bibliographyEntryId,
            )
            true
        } ?: false

    fun isObjectKeyReferenced(objectKey: String): Boolean = jdbc.queryForObject(
        "SELECT EXISTS (SELECT 1 FROM cited_paper_access WHERE object_key = ?)",
        Boolean::class.java,
        objectKey,
    ) == true

    fun reportEntries(analysisRunId: UUID): Map<String, CitedPaperAccessReport> = jdbc.query(
        """
        SELECT b.local_reference_key, a.access_status, a.access_reason, a.provider_id, a.source_url, a.license_identifier,
               a.location_version, a.location_host_type, a.discovered_at, a.object_key, a.content_sha256,
               a.language, a.language_detector_version,
               COALESCE(
                   jsonb_agg(jsonb_build_object(
                       'atomicClaimId', verification.atomic_claim_id,
                       'finalStatus', verification.final_status,
                       'verificationScope', verification.verification_scope,
                       'terminalReason', verification.terminal_reason
                   ) ORDER BY verification.atomic_claim_id) FILTER (WHERE verification.id IS NOT NULL),
                   '[]'::jsonb
               )::text AS verification_outcomes
          FROM cited_paper_access a
          JOIN bibliography_entries b
            ON b.analysis_run_id = a.analysis_run_id AND b.id = a.bibliography_entry_id
          LEFT JOIN claim_paper_verifications verification
            ON verification.analysis_run_id = a.analysis_run_id
           AND verification.bibliography_entry_id = a.bibliography_entry_id
         WHERE a.analysis_run_id = ?
         GROUP BY b.local_reference_key, a.access_status, a.access_reason, a.provider_id, a.source_url, a.license_identifier,
                  a.location_version, a.location_host_type, a.discovered_at, a.object_key, a.content_sha256,
                  a.language, a.language_detector_version
        """.trimIndent(),
        { rs, _ -> rs.toAccessReportEntry() },
        analysisRunId,
    ).associate { it.localReferenceKey to it.report }

    private fun ResultSet.toAccessReportEntry(): AccessReportEntry {
        val outcomeType = objectMapper.typeFactory.constructCollectionType(List::class.java, CitedReferenceVerificationOutcome::class.java)
        val outcomes = objectMapper.readValue<List<CitedReferenceVerificationOutcome>>(getString("verification_outcomes"), outcomeType)
        return AccessReportEntry(
            localReferenceKey = getString("local_reference_key"),
            report = CitedPaperAccessReport(
                accessStatus = getString("access_status"),
                accessReason = getString("access_reason"),
                providerId = getString("provider_id"),
                sourceUrl = getString("source_url"),
                license = getString("license_identifier"),
                version = getString("location_version"),
                hostType = getString("location_host_type"),
                discoveredAt = getTimestamp("discovered_at").toInstant(),
                contentSha256 = getString("content_sha256"),
                language = getString("language"),
                languageDetectorVersion = getString("language_detector_version"),
                verificationOutcomes = outcomes,
            ),
        )
    }


    private data class AccessReportEntry(
        val localReferenceKey: String,
        val report: CitedPaperAccessReport,
    )
}
