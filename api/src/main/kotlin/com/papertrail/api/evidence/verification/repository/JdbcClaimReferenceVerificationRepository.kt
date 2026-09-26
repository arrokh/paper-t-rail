package com.papertrail.api.evidence.verification.repository

import com.papertrail.api.evidence.verification.domain.EvidenceAggregationDecision
import com.papertrail.api.scholarly.acquisition.domain.CitedPaperAccessDecision
import com.papertrail.api.scholarly.acquisition.domain.VerificationScope
import com.papertrail.api.scholarly.references.resolver.ReferenceResolutionStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class JdbcClaimReferenceVerificationRepository(
    private val jdbc: JdbcTemplate,
) : ClaimReferenceVerificationRepository {
    override fun initializeExpectedPairs(analysisRunId: UUID) {
        jdbc.update(
            """
            INSERT INTO claim_paper_verifications (
                id, analysis_run_id, atomic_claim_id, bibliography_entry_id,
                canonical_paper_id, processing_status, verification_scope
            )
            SELECT gen_random_uuid(), link.analysis_run_id, link.atomic_claim_id, target.bibliography_entry_id,
                   NULL, 'PENDING', 'NONE'
              FROM atomic_claim_citation_targets link
              JOIN citation_targets target
                ON target.analysis_run_id = link.analysis_run_id
               AND target.id = link.citation_target_id
             WHERE link.analysis_run_id = ?
            ON CONFLICT (analysis_run_id, atomic_claim_id, bibliography_entry_id) DO NOTHING
            """.trimIndent(),
            analysisRunId,
        )
    }

    override fun applyResolution(
        analysisRunId: UUID,
        bibliographyEntryId: UUID,
        status: ReferenceResolutionStatus,
        reason: String,
        canonicalPaperId: UUID?,
    ) {
        val finalStatus = when (status) {
            ReferenceResolutionStatus.RESOLVED -> null
            ReferenceResolutionStatus.UNRESOLVED -> "UNRESOLVED"
            ReferenceResolutionStatus.UNSUPPORTED_REFERENCE_TYPE -> "UNSUPPORTED_REFERENCE_TYPE"
        }
        if (status == ReferenceResolutionStatus.RESOLVED) {
            requireNotNull(canonicalPaperId) { "Resolved reference has no Canonical Paper." }
        } else {
            require(canonicalPaperId == null) { "Unresolved reference must not have a Canonical Paper." }
        }
        jdbc.update(
            """
            UPDATE claim_paper_verifications
               SET canonical_paper_id = ?,
                   verification_scope = 'NONE',
                   terminal_reason = ?,
                   processing_status = CASE WHEN ? THEN 'PENDING' ELSE 'COMPLETED' END,
                   final_status = ?
             WHERE analysis_run_id = ? AND bibliography_entry_id = ? AND processing_status = 'PENDING'
            """.trimIndent(),
            canonicalPaperId,
            reason.takeIf { finalStatus != null },
            finalStatus == null,
            finalStatus,
            analysisRunId,
            bibliographyEntryId,
        )
    }

    override fun applyAccessDecision(
        analysisRunId: UUID,
        bibliographyEntryId: UUID,
        canonicalPaperId: UUID,
        decision: CitedPaperAccessDecision,
    ) {
        jdbc.update(
            """
            UPDATE claim_paper_verifications
               SET canonical_paper_id = ?,
                   verification_scope = ?,
                   terminal_reason = ?,
                   processing_status = CASE WHEN ? THEN 'PENDING' ELSE 'COMPLETED' END,
                   final_status = ?
             WHERE analysis_run_id = ? AND bibliography_entry_id = ? AND processing_status = 'PENDING'
            """.trimIndent(),
            canonicalPaperId,
            decision.verificationScope.name,
            decision.terminalReason,
            decision.finalVerificationStatus == null,
            decision.finalVerificationStatus?.name,
            analysisRunId,
            bibliographyEntryId,
        )
    }

    override fun isCompleted(verificationId: UUID): Boolean = jdbc.queryForObject(
        "SELECT EXISTS (SELECT 1 FROM claim_paper_verifications WHERE id = ? AND processing_status = 'COMPLETED')",
        Boolean::class.java,
        verificationId,
    ) == true

    override fun complete(
        verificationId: UUID,
        decision: EvidenceAggregationDecision,
        aggregatorVersion: String,
    ): Boolean = jdbc.update(
        """
        UPDATE claim_paper_verifications
           SET processing_status = 'COMPLETED',
               final_status = ?,
               evidence_conflict = ?,
               aggregator_version = ?,
               updated_at = now()
         WHERE id = ? AND processing_status = 'PENDING' AND verification_scope = 'FULL_TEXT'
        """.trimIndent(),
        decision.finalStatus.name,
        decision.evidenceConflict,
        aggregatorVersion,
        verificationId,
    ) == 1

    override fun failReference(analysisRunId: UUID, bibliographyEntryId: UUID, reason: String) {
        failPending(analysisRunId, bibliographyEntryId, reason, null)
    }

    override fun failFullText(analysisRunId: UUID, bibliographyEntryId: UUID, reason: String) {
        failPending(analysisRunId, bibliographyEntryId, reason, VerificationScope.FULL_TEXT.name)
    }

    private fun failPending(
        analysisRunId: UUID,
        bibliographyEntryId: UUID,
        reason: String,
        scope: String?,
    ) {
        val scopeCondition = if (scope == null) "" else "AND verification_scope = ?"
        val parameters = if (scope == null) {
            arrayOf<Any?>(reason, analysisRunId, bibliographyEntryId)
        } else {
            arrayOf<Any?>(reason, analysisRunId, bibliographyEntryId, scope)
        }
        jdbc.update(
            """
            UPDATE claim_paper_verifications
               SET processing_status = 'FAILED', processing_failure_reason = ?, updated_at = now()
             WHERE analysis_run_id = ? AND bibliography_entry_id = ?
               AND processing_status = 'PENDING'
               $scopeCondition
            """.trimIndent(),
            *parameters,
        )
    }
}
