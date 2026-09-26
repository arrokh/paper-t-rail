package com.papertrail.api.scholarly.acquisition.repository

import com.fasterxml.jackson.databind.ObjectMapper
import com.papertrail.api.evidence.report.EvidenceIndexingReport
import com.papertrail.api.evidence.report.EvidencePassageReport
import com.papertrail.api.evidence.repository.EvidenceReportRepository
import com.papertrail.api.infrastructure.crypto.sha256Hex
import com.papertrail.api.scholarly.acquisition.domain.AcquiredFullText
import com.papertrail.api.scholarly.acquisition.domain.CitedPaperAccessDecision
import com.papertrail.api.scholarly.acquisition.domain.CitedPaperAccessReason
import com.papertrail.api.scholarly.acquisition.domain.OpenAccessDiscovery
import com.papertrail.api.scholarly.acquisition.report.CitedPaperAccessReport
import com.papertrail.api.scholarly.acquisition.report.CitedReferenceVerificationOutcome
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
    private val evidenceReportRepository: EvidenceReportRepository,
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
                    content_media_type, language, language_detector_version
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
                fullText?.mediaType?.substringBefore(';')?.trim()?.lowercase(),
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

    fun reportEntries(analysisRunId: UUID): Map<String, CitedPaperAccessReport> {
        val indexingByReference = evidenceReportRepository.indexingReportsByReference(analysisRunId)
        val passagesByVerification = evidenceReportRepository.passagesByVerification(analysisRunId)
        val outcomesByReference = verificationOutcomes(analysisRunId, passagesByVerification)
        return jdbc.query(
            """
            SELECT b.id AS bibliography_entry_id, b.local_reference_key, a.access_status, a.access_reason,
                   a.provider_id, a.source_url, a.license_identifier, a.location_version, a.location_host_type,
                   a.discovered_at, a.content_sha256, a.language, a.language_detector_version
              FROM cited_paper_access a
              JOIN bibliography_entries b
                ON b.analysis_run_id = a.analysis_run_id AND b.id = a.bibliography_entry_id
             WHERE a.analysis_run_id = ?
             ORDER BY b.entry_order
            """.trimIndent(),
            { rs, _ -> rs.toAccessReportEntry(outcomesByReference, indexingByReference) },
            analysisRunId,
        ).associate { it.localReferenceKey to it.report }
    }

    private fun verificationOutcomes(
        analysisRunId: UUID,
        passagesByVerification: Map<UUID, List<EvidencePassageReport>>,
    ): Map<UUID, List<CitedReferenceVerificationOutcome>> = jdbc.query(
        """
        SELECT verification.id AS verification_id,
               verification.bibliography_entry_id,
               verification.atomic_claim_id,
               claim.claim_text,
               verification.final_status,
               verification.verification_scope,
               verification.terminal_reason
          FROM claim_paper_verifications verification
          JOIN atomic_claims claim
            ON claim.analysis_run_id = verification.analysis_run_id
           AND claim.id = verification.atomic_claim_id
         WHERE verification.analysis_run_id = ?
         ORDER BY claim.source_start_offset, verification.atomic_claim_id
        """.trimIndent(),
        { rs, _ ->
            val verificationId = rs.getObject("verification_id", UUID::class.java)
            VerificationOutcomeRow(
                bibliographyEntryId = rs.getObject("bibliography_entry_id", UUID::class.java),
                outcome = CitedReferenceVerificationOutcome(
                    atomicClaimId = rs.getObject("atomic_claim_id", UUID::class.java),
                    claimText = rs.getString("claim_text"),
                    finalStatus = rs.getString("final_status"),
                    verificationScope = rs.getString("verification_scope"),
                    terminalReason = rs.getString("terminal_reason"),
                    evidencePassages = passagesByVerification[verificationId].orEmpty(),
                ),
            )
        },
        analysisRunId,
    ).groupBy(VerificationOutcomeRow::bibliographyEntryId)
        .mapValues { (_, rows) -> rows.map(VerificationOutcomeRow::outcome) }

    private fun ResultSet.toAccessReportEntry(
        outcomesByReference: Map<UUID, List<CitedReferenceVerificationOutcome>>,
        indexingByReference: Map<UUID, EvidenceIndexingReport>,
    ): AccessReportEntry {
        val bibliographyEntryId = getObject("bibliography_entry_id", UUID::class.java)
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
                verificationOutcomes = outcomesByReference[bibliographyEntryId].orEmpty(),
                evidenceIndexing = indexingByReference[bibliographyEntryId],
            ),
        )
    }

    private data class VerificationOutcomeRow(
        val bibliographyEntryId: UUID,
        val outcome: CitedReferenceVerificationOutcome,
    )

    private data class AccessReportEntry(
        val localReferenceKey: String,
        val report: CitedPaperAccessReport,
    )
}
