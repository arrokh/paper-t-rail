package com.papertrail.api.evidence.verification.repository

import com.papertrail.api.utils.JsonUtil
import com.papertrail.api.analysis.configuration.AnalysisConfigurationSnapshot
import com.papertrail.api.evidence.verification.domain.EvidenceJudgement
import com.papertrail.api.evidence.verification.domain.EvidenceJudgementKind
import com.papertrail.api.evidence.verification.domain.EvidencePassageForJudgement
import com.papertrail.api.evidence.verification.domain.EvidenceRole
import com.papertrail.api.evidence.verification.domain.SemanticJudgementRequest
import com.papertrail.api.evidence.verification.domain.AtomicClaimForJudgement
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID

@Repository
class EvidenceJudgementRepository(
    private val jdbc: JdbcTemplate,
    private val transactionTemplate: TransactionTemplate,
) {
    fun loadProcessingConfiguration(analysisRunId: UUID): AnalysisConfigurationSnapshot = jdbc.query(
        "SELECT configuration_snapshot::text FROM analysis_runs WHERE id = ? AND status = 'PROCESSING'",
        { rs, _ -> JsonUtil.fromJson(rs.getString(1), AnalysisConfigurationSnapshot::class.java) },
        analysisRunId,
    ).firstOrNull() ?: throw IllegalStateException("Analysis Run is not available for semantic verification.")

    fun pendingRequests(analysisRunId: UUID, bibliographyEntryId: UUID): List<PendingJudgement> {
        val rows = jdbc.query(
            """
            SELECT verification.id AS verification_id,
                   claim.id AS atomic_claim_id,
                   claim.claim_text,
                   candidate.id AS candidate_id,
                   chunk.text AS evidence_text,
                   chunk.section_heading
              FROM claim_paper_verifications verification
              JOIN atomic_claims claim
                ON claim.analysis_run_id = verification.analysis_run_id
               AND claim.id = verification.atomic_claim_id
              LEFT JOIN evidence_candidates candidate
                ON candidate.verification_id = verification.id
               AND candidate.analysis_run_id = verification.analysis_run_id
               AND candidate.bibliography_entry_id = verification.bibliography_entry_id
              LEFT JOIN paper_chunks chunk
                ON chunk.id = candidate.paper_chunk_id
               AND chunk.analysis_run_id = candidate.analysis_run_id
               AND chunk.bibliography_entry_id = candidate.bibliography_entry_id
             WHERE verification.analysis_run_id = ?
               AND verification.bibliography_entry_id = ?
               AND verification.processing_status = 'PENDING'
               AND verification.verification_scope = 'FULL_TEXT'
             ORDER BY claim.source_start_offset, claim.id, candidate.fused_rank
            """.trimIndent(),
            { rs, _ ->
                PendingRow(
                    verificationId = rs.getObject("verification_id", UUID::class.java),
                    claim = AtomicClaimForJudgement(
                        id = rs.getObject("atomic_claim_id", UUID::class.java),
                        text = rs.getString("claim_text"),
                    ),
                    passage = rs.getObject("candidate_id", UUID::class.java)?.let { candidateId ->
                        EvidencePassageForJudgement(
                            id = candidateId,
                            text = rs.getString("evidence_text"),
                            sectionHeading = rs.getString("section_heading"),
                        )
                    },
                )
            },
            analysisRunId,
            bibliographyEntryId,
        )
        return rows.groupBy(PendingRow::verificationId).map { (verificationId, verificationRows) ->
            val first = verificationRows.first()
            PendingJudgement(
                verificationId = verificationId,
                request = SemanticJudgementRequest(
                    atomicClaim = first.claim,
                    evidencePassages = verificationRows.mapNotNull(PendingRow::passage),
                ),
            )
        }
    }

    fun persistedEvidenceCandidateIds(verificationId: UUID, evidenceCandidateIds: Set<UUID>): Set<UUID> {
        if (evidenceCandidateIds.isEmpty()) return emptySet()
        val placeholders = evidenceCandidateIds.joinToString(", ") { "?" }
        return jdbc.query(
            """
            SELECT evidence_candidate_id
              FROM evidence_judgements
             WHERE verification_id = ? AND evidence_candidate_id IN ($placeholders)
            """.trimIndent(),
            { rs, _ -> rs.getObject("evidence_candidate_id", UUID::class.java) },
            verificationId,
            *evidenceCandidateIds.toTypedArray(),
        ).toSet()
    }

    fun persistAndLoad(
        analysisRunId: UUID,
        bibliographyEntryId: UUID,
        verificationId: UUID,
        providerId: String,
        modelId: String?,
        providerVersion: String,
        judgements: List<EvidenceJudgement>,
    ): List<EvidenceJudgement> = transactionTemplate.execute {
        judgements.forEach { judgement ->
            jdbc.update(
                """
                INSERT INTO evidence_judgements (
                    id, evidence_candidate_id, verification_id, analysis_run_id, bibliography_entry_id,
                    system_one_provider, system_one_model, system_one_version, judgement, evidence_role,
                    confidence, directness, claim_scope_match, study_design_quality, relevance, raw_scores
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                ON CONFLICT (evidence_candidate_id) DO NOTHING
                """.trimIndent(),
                UUID.randomUUID(),
                judgement.evidenceCandidateId,
                verificationId,
                analysisRunId,
                bibliographyEntryId,
                providerId,
                judgement.providerReportedModelId ?: modelId,
                providerVersion,
                judgement.judgement.name,
                judgement.evidenceRole.name,
                judgement.confidence,
                judgement.directness,
                judgement.claimScopeMatch,
                judgement.studyDesignQuality,
                judgement.relevance,
                JsonUtil.toJson(judgement.rawScores()),
            )
        }
        jdbc.query(
            """
            SELECT evidence_candidate_id, judgement, evidence_role, confidence, directness,
                   claim_scope_match, study_design_quality, relevance
              FROM evidence_judgements
             WHERE verification_id = ?
             ORDER BY evidence_candidate_id
            """.trimIndent(),
            { rs, _ ->
                EvidenceJudgement(
                    evidenceCandidateId = rs.getObject("evidence_candidate_id", UUID::class.java),
                    judgement = EvidenceJudgementKind.valueOf(rs.getString("judgement")),
                    evidenceRole = EvidenceRole.valueOf(rs.getString("evidence_role")),
                    confidence = rs.getDouble("confidence"),
                    directness = rs.getDouble("directness"),
                    claimScopeMatch = rs.getDouble("claim_scope_match"),
                    studyDesignQuality = rs.getDouble("study_design_quality"),
                    relevance = rs.getDouble("relevance"),
                )
            },
            verificationId,
        )
    } ?: throw IllegalStateException("Evidence Judgements could not be persisted.")

    private data class PendingRow(
        val verificationId: UUID,
        val claim: AtomicClaimForJudgement,
        val passage: EvidencePassageForJudgement?,
    )

}
