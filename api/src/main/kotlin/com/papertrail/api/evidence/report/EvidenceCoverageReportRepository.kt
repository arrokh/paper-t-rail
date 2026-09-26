package com.papertrail.api.evidence.report

import com.papertrail.api.evidence.repository.EvidenceReportRepository
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.util.UUID

@Repository
class EvidenceCoverageReportRepository(
    private val jdbc: JdbcTemplate,
    private val evidenceReportRepository: EvidenceReportRepository,
) {
    fun indexingReportsByReference(analysisRunId: UUID): Map<UUID, EvidenceIndexingReport> =
        evidenceReportRepository.indexingReportsByReference(analysisRunId)

    fun summary(analysisRunId: UUID): EvidenceCoverageSummary = jdbc.queryForObject(
        """
        SELECT count(*)::integer AS total,
               count(*) FILTER (WHERE processing_status = 'COMPLETED')::integer AS completed,
               count(*) FILTER (WHERE processing_status <> 'COMPLETED')::integer AS incomplete,
               count(*) FILTER (WHERE evidence_conflict)::integer AS conflicts,
               count(*) FILTER (WHERE final_status = 'SUPPORTED')::integer AS supported,
               count(*) FILTER (WHERE final_status = 'PARTIALLY_SUPPORTED')::integer AS partially_supported,
               count(*) FILTER (WHERE final_status = 'CONTRADICTED')::integer AS contradicted,
               count(*) FILTER (WHERE final_status = 'INSUFFICIENT_EVIDENCE')::integer AS insufficient_evidence,
               count(*) FILTER (WHERE final_status = 'INACCESSIBLE')::integer AS inaccessible,
               count(*) FILTER (WHERE final_status = 'UNRESOLVED')::integer AS unresolved,
               count(*) FILTER (WHERE final_status = 'UNSUPPORTED_REFERENCE_TYPE')::integer AS unsupported_reference_type
          FROM claim_paper_verifications
         WHERE analysis_run_id = ?
        """.trimIndent(),
        { rs, _ -> rs.toCoverageSummary() },
        analysisRunId,
    ) ?: EvidenceCoverageSummary(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)

    fun outcomesByReference(analysisRunId: UUID): Map<String, List<CitedReferenceVerificationOutcome>> {
        val passages = evidenceReportRepository.passagesByVerification(analysisRunId)
        return jdbc.query(
            """
            SELECT verification.id AS verification_id,
                   reference.local_reference_key,
                   verification.atomic_claim_id,
                   claim.claim_text,
                   claim.source_start_offset,
                   claim.source_end_offset,
                   context.context_text,
                   COALESCE(
                       array_agg(DISTINCT occurrence.marker_text ORDER BY occurrence.marker_text)
                           FILTER (WHERE occurrence.marker_text IS NOT NULL),
                       ARRAY[]::text[]
                   ) AS citation_markers,
                   verification.processing_status,
                   verification.processing_failure_reason,
                   verification.final_status,
                   verification.verification_scope,
                   verification.terminal_reason,
                   verification.evidence_conflict,
                   verification.aggregator_version
              FROM claim_paper_verifications verification
              JOIN bibliography_entries reference
                ON reference.analysis_run_id = verification.analysis_run_id
               AND reference.id = verification.bibliography_entry_id
              JOIN atomic_claims claim
                ON claim.analysis_run_id = verification.analysis_run_id
               AND claim.id = verification.atomic_claim_id
              JOIN citation_contexts context
                ON context.analysis_run_id = claim.analysis_run_id
               AND context.id = claim.citation_context_id
              LEFT JOIN atomic_claim_citation_targets link
                ON link.analysis_run_id = claim.analysis_run_id
               AND link.atomic_claim_id = claim.id
              LEFT JOIN citation_targets target
                ON target.analysis_run_id = link.analysis_run_id
               AND target.id = link.citation_target_id
               AND target.bibliography_entry_id = verification.bibliography_entry_id
              LEFT JOIN citation_occurrences occurrence
                ON occurrence.analysis_run_id = target.analysis_run_id
               AND occurrence.id = target.citation_occurrence_id
             WHERE verification.analysis_run_id = ?
             GROUP BY verification.id, verification.bibliography_entry_id, reference.local_reference_key,
                      verification.atomic_claim_id, claim.claim_text, claim.source_start_offset, claim.source_end_offset,
                      context.context_text, verification.processing_status,
                      verification.processing_failure_reason, verification.final_status,
                      verification.verification_scope, verification.terminal_reason,
                      verification.evidence_conflict, verification.aggregator_version
             ORDER BY verification.bibliography_entry_id, claim.source_start_offset, verification.atomic_claim_id
            """.trimIndent(),
            { rs, _ -> rs.toVerificationOutcome(passages) },
            analysisRunId,
        ).groupBy({ it.first }, { it.second })
    }

    private fun ResultSet.toCoverageSummary() = EvidenceCoverageSummary(
        totalVerifications = getInt("total"),
        completedVerifications = getInt("completed"),
        incompleteVerifications = getInt("incomplete"),
        evidenceConflicts = getInt("conflicts"),
        supported = getInt("supported"),
        partiallySupported = getInt("partially_supported"),
        contradicted = getInt("contradicted"),
        insufficientEvidence = getInt("insufficient_evidence"),
        inaccessible = getInt("inaccessible"),
        unresolved = getInt("unresolved"),
        unsupportedReferenceType = getInt("unsupported_reference_type"),
    )

    private fun ResultSet.toVerificationOutcome(
        passages: Map<UUID, List<EvidencePassageReport>>,
    ): Pair<String, CitedReferenceVerificationOutcome> {
        val verificationId = getObject("verification_id", UUID::class.java)
        val sqlMarkers = getArray("citation_markers")?.array as? Array<*>
        val status = getString("processing_status")
        return getString("local_reference_key") to CitedReferenceVerificationOutcome(
            id = verificationId,
            atomicClaimId = getObject("atomic_claim_id", UUID::class.java),
            claimText = getString("claim_text"),
            claimSourceStartOffset = getInt("source_start_offset"),
            claimSourceEndOffset = getInt("source_end_offset"),
            citationContextText = getString("context_text"),
            citationMarkers = sqlMarkers.orEmpty().mapNotNull { it as? String },
            associationKind = "INFERRED_PROVISIONAL",
            processingStatus = if (status == "FAILED") "INCOMPLETE" else status,
            processingFailureReason = getString("processing_failure_reason"),
            finalStatus = getString("final_status"),
            verificationScope = getString("verification_scope"),
            terminalReason = getString("terminal_reason"),
            evidenceConflict = getBoolean("evidence_conflict"),
            aggregatorVersion = getString("aggregator_version"),
            evidencePassages = passages[verificationId].orEmpty(),
        )
    }
}
