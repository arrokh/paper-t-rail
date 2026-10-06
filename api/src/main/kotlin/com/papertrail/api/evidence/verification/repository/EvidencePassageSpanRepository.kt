package com.papertrail.api.evidence.verification.repository

import com.fasterxml.jackson.core.type.TypeReference
import com.papertrail.api.utils.JsonUtil
import com.papertrail.api.evidence.verification.domain.EvidenceJudgement
import com.papertrail.api.evidence.verification.domain.EvidencePassageSpanDefinition
import com.papertrail.api.evidence.verification.domain.LayaEvidencePassageSpanPlanner
import com.papertrail.api.evidence.report.EvidenceJudgementReport
import com.papertrail.api.evidence.report.EvidencePassageSpanReport
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID

@Repository
class EvidencePassageSpanRepository(
    private val jdbc: JdbcTemplate,
    private val transactionTemplate: TransactionTemplate,
) {
    fun prepare(
        analysisRunId: UUID,
        bibliographyEntryId: UUID,
        verificationId: UUID,
        evidenceCandidateId: UUID,
        definitions: List<EvidencePassageSpanDefinition>,
        providerId: String,
        modelId: String,
        providerVersion: String,
        rubricVersion: String,
    ): List<PersistedEvidencePassageSpan> = transactionTemplate.execute {
        definitions.forEach { definition ->
            jdbc.update(
                """
                INSERT INTO laya_evidence_passage_spans (
                    id, evidence_candidate_id, verification_id, analysis_run_id, bibliography_entry_id,
                    splitting_policy_version, span_index, core_start_offset, core_end_offset,
                    context_start_offset, context_end_offset, token_counts,
                    system_one_provider, system_one_model, system_one_version, judgement_rubric_version,
                    status, failure_reason
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (evidence_candidate_id, splitting_policy_version, span_index) DO NOTHING
                """.trimIndent(),
                UUID.randomUUID(),
                evidenceCandidateId,
                verificationId,
                analysisRunId,
                bibliographyEntryId,
                LayaEvidencePassageSpanPlanner.SPLITTING_POLICY_VERSION,
                definition.spanIndex,
                definition.coreStartOffset,
                definition.coreEndOffset,
                definition.contextStartOffset,
                definition.contextEndOffset,
                JsonUtil.toJson(definition.tokenCounts),
                providerId,
                modelId,
                providerVersion,
                rubricVersion,
                if (definition.isIncomplete) "INCOMPLETE" else "PENDING",
                definition.incompleteReason,
            )
        }

        val persisted = load(evidenceCandidateId)
            .filter { it.definitionPolicyVersion == LayaEvidencePassageSpanPlanner.SPLITTING_POLICY_VERSION }
            .sortedBy { it.definition.spanIndex }
        require(persisted.size == definitions.size) { "Persisted Laya span definitions do not match the planned spans." }
        definitions.zip(persisted).forEach { (expected, actual) ->
            require(actual.definition == expected &&
                actual.providerId == providerId && actual.modelId == modelId &&
                actual.providerVersion == providerVersion && actual.rubricVersion == rubricVersion
            ) { "Persisted Laya span provenance does not match the pinned Analysis Run evaluation." }
        }
        persisted
    } ?: throw IllegalStateException("Laya Evidence Passage spans could not be persisted.")

    fun retryFailed(spanId: UUID) {
        jdbc.update(
            """
            UPDATE laya_evidence_passage_spans
               SET status = 'PENDING', failure_reason = NULL, updated_at = now()
             WHERE id = ? AND status = 'FAILED'
            """.trimIndent(),
            spanId,
        )
    }

    fun complete(spanId: UUID, judgement: EvidenceJudgement) {
        jdbc.update(
            """
            UPDATE laya_evidence_passage_spans
               SET status = 'COMPLETED',
                   failure_reason = NULL,
                   judgement = ?,
                   evidence_role = ?,
                   confidence = ?,
                   directness = ?,
                   claim_scope_match = ?,
                   study_design_quality = ?,
                   relevance = ?,
                   raw_scores = ?::jsonb,
                   updated_at = now()
             WHERE id = ? AND status = 'PENDING'
            """.trimIndent(),
            judgement.judgement.name,
            judgement.evidenceRole.name,
            judgement.confidence,
            judgement.directness,
            judgement.claimScopeMatch,
            judgement.studyDesignQuality,
            judgement.relevance,
            JsonUtil.toJson(judgement.rawScores()),
            spanId,
        )
    }

    fun failPendingForReference(analysisRunId: UUID, bibliographyEntryId: UUID, reason: String) {
        jdbc.update(
            """
            UPDATE laya_evidence_passage_spans
               SET status = 'FAILED', failure_reason = ?, updated_at = now()
             WHERE analysis_run_id = ? AND bibliography_entry_id = ? AND status = 'PENDING'
            """.trimIndent(),
            reason,
            analysisRunId,
            bibliographyEntryId,
        )
    }

    fun fail(spanId: UUID, reason: String) {
        jdbc.update(
            """
            UPDATE laya_evidence_passage_spans
               SET status = 'FAILED', failure_reason = ?, updated_at = now()
             WHERE id = ? AND status = 'PENDING'
            """.trimIndent(),
            reason,
            spanId,
        )
    }

    fun diagnosticReportsByEvidenceCandidate(evidenceCandidateIds: Set<UUID>): Map<UUID, List<EvidencePassageSpanReport>> {
        if (evidenceCandidateIds.isEmpty()) return emptyMap()
        val placeholders = evidenceCandidateIds.joinToString(", ") { "?" }
        val parameters = evidenceCandidateIds.map<UUID, Any> { it } + LayaEvidencePassageSpanPlanner.SPLITTING_POLICY_VERSION
        return jdbc.query(
            """
            SELECT span.evidence_candidate_id,
                   span.id,
                   span.span_index,
                   span.core_start_offset,
                   span.core_end_offset,
                   span.context_start_offset,
                   span.context_end_offset,
                   span.token_counts::text AS token_counts,
                   span.status,
                   span.failure_reason,
                   span.system_one_provider,
                   span.system_one_model,
                   span.system_one_version,
                   span.judgement_rubric_version,
                   span.splitting_policy_version,
                   span.judgement,
                   span.evidence_role,
                   span.confidence,
                   span.directness,
                   span.claim_scope_match,
                   span.study_design_quality,
                   span.relevance,
                   (span.raw_scores ->> 'calibratedStrength')::double precision AS calibrated_strength,
                   chunk.text AS parent_text
              FROM laya_evidence_passage_spans span
              JOIN evidence_candidates candidate
                ON candidate.id = span.evidence_candidate_id
               AND candidate.verification_id = span.verification_id
               AND candidate.analysis_run_id = span.analysis_run_id
               AND candidate.bibliography_entry_id = span.bibliography_entry_id
              JOIN paper_chunks chunk
                ON chunk.id = candidate.paper_chunk_id
               AND chunk.analysis_run_id = candidate.analysis_run_id
               AND chunk.bibliography_entry_id = candidate.bibliography_entry_id
             WHERE span.evidence_candidate_id IN ($placeholders)
               AND span.splitting_policy_version = ?
             ORDER BY span.evidence_candidate_id, span.span_index
            """.trimIndent(),
            { rs, _ ->
                val parentText = rs.getString("parent_text")
                val coreStart = rs.getInt("core_start_offset")
                val coreEnd = rs.getInt("core_end_offset")
                val contextStart = rs.getInt("context_start_offset")
                val contextEnd = rs.getInt("context_end_offset")
                require(coreEnd <= parentText.length && contextEnd <= parentText.length) {
                    "Persisted Laya span offsets exceed the original Evidence Passage."
                }
                val status = rs.getString("status")
                val judgement = if (status == "COMPLETED") {
                    EvidenceJudgementReport(
                        providerId = rs.getString("system_one_provider"),
                        modelId = rs.getString("system_one_model"),
                        providerVersion = rs.getString("system_one_version"),
                        judgement = rs.getString("judgement"),
                        evidenceRole = rs.getString("evidence_role"),
                        confidence = rs.getDouble("confidence"),
                        directness = rs.getDouble("directness"),
                        claimScopeMatch = rs.getDouble("claim_scope_match"),
                        studyDesignQuality = rs.getDouble("study_design_quality"),
                        relevance = rs.getDouble("relevance"),
                        calibratedStrength = rs.getDouble("calibrated_strength"),
                    )
                } else {
                    null
                }
                rs.getObject("evidence_candidate_id", UUID::class.java) to EvidencePassageSpanReport(
                    id = rs.getObject("id", UUID::class.java),
                    spanIndex = rs.getInt("span_index"),
                    coreStartOffset = coreStart,
                    coreEndOffset = coreEnd,
                    contextStartOffset = contextStart,
                    contextEndOffset = contextEnd,
                    coreText = parentText.substring(coreStart, coreEnd),
                    contextText = parentText.substring(contextStart, contextEnd),
                    tokenCounts = JsonUtil.fromJson(rs.getString("token_counts"), object : TypeReference<List<Int>>() {}),
                    status = status,
                    failureReason = rs.getString("failure_reason"),
                    providerId = rs.getString("system_one_provider"),
                    modelId = rs.getString("system_one_model"),
                    providerVersion = rs.getString("system_one_version"),
                    judgementRubricVersion = rs.getString("judgement_rubric_version"),
                    splittingPolicyVersion = rs.getString("splitting_policy_version"),
                    evidenceJudgement = judgement,
                )
            },
            *parameters.toTypedArray(),
        ).groupBy({ it.first }, { it.second })
    }

    private fun load(evidenceCandidateId: UUID): List<PersistedEvidencePassageSpan> = jdbc.query(
        """
        SELECT id, splitting_policy_version, span_index,
               core_start_offset, core_end_offset, context_start_offset, context_end_offset,
               token_counts::text AS token_counts, status, failure_reason,
               system_one_provider, system_one_model, system_one_version, judgement_rubric_version
          FROM laya_evidence_passage_spans
         WHERE evidence_candidate_id = ?
         ORDER BY splitting_policy_version, span_index
        """.trimIndent(),
        { rs, _ ->
            val status = rs.getString("status")
            val failureReason = rs.getString("failure_reason")
            PersistedEvidencePassageSpan(
                id = rs.getObject("id", UUID::class.java),
                definition = EvidencePassageSpanDefinition(
                    spanIndex = rs.getInt("span_index"),
                    coreStartOffset = rs.getInt("core_start_offset"),
                    coreEndOffset = rs.getInt("core_end_offset"),
                    contextStartOffset = rs.getInt("context_start_offset"),
                    contextEndOffset = rs.getInt("context_end_offset"),
                    tokenCounts = JsonUtil.fromJson(rs.getString("token_counts"), object : TypeReference<List<Int>>() {}),
                    incompleteReason = failureReason.takeIf { status == "INCOMPLETE" },
                ),
                status = status,
                failureReason = failureReason,
                definitionPolicyVersion = rs.getString("splitting_policy_version"),
                providerId = rs.getString("system_one_provider"),
                modelId = rs.getString("system_one_model"),
                providerVersion = rs.getString("system_one_version"),
                rubricVersion = rs.getString("judgement_rubric_version"),
            )
        },
        evidenceCandidateId,
    )
}
