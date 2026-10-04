package com.papertrail.api.analysis.service

import com.papertrail.api.citation.parsing.ParsedDocumentRepository
import com.papertrail.api.document.service.lockActiveAnalysisRun
import com.papertrail.api.evidence.queue.CITED_PAPER_INDEXING_REQUESTED
import com.papertrail.api.evidence.verification.provider.JevSystemOneSettings
import com.papertrail.api.evidence.verification.provider.LayaSystemOneSettings
import com.papertrail.api.scholarly.acquisition.queue.CITED_PAPER_ACQUISITION_REQUESTED
import com.papertrail.api.scholarly.references.queue.REFERENCE_RESOLUTION_REQUESTED
import com.papertrail.api.scholarly.references.service.ReferenceResolutionService
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID

@Component
class AnalysisRunStageCompletionService(
    private val jdbc: JdbcTemplate,
    private val transactionTemplate: TransactionTemplate,
    private val parsedDocumentRepository: ParsedDocumentRepository,
    private val referenceResolutionService: ReferenceResolutionService,
) {
    fun completeParsedStageIfReady(analysisRunId: UUID): Boolean = transactionTemplate.execute {
        jdbc.lockActiveAnalysisRun(analysisRunId)
        val runStatus = jdbc.query(
            "SELECT status FROM analysis_runs WHERE id = ? FOR UPDATE",
            { rs, _ -> rs.getString("status") },
            analysisRunId,
        ).firstOrNull() ?: throw IllegalArgumentException("Analysis Run not found while completing parsed stage.")
        if (runStatus != "PROCESSING") return@execute false

        val pendingTaskCount = jdbc.queryForObject(
            """
            SELECT count(*)
              FROM outbox_events task
             WHERE task.analysis_run_id = ?
               AND task.event_type = ?
               AND NOT EXISTS (SELECT 1 FROM inbox_events completed WHERE completed.event_id = task.event_id)
            """.trimIndent(),
            Long::class.java,
            analysisRunId,
            REFERENCE_RESOLUTION_REQUESTED,
        ) ?: 0L
        val pendingAcquisitionCount = jdbc.queryForObject(
            """
            SELECT count(*)
              FROM outbox_events task
             WHERE task.analysis_run_id = ?
               AND task.event_type = ?
               AND NOT EXISTS (SELECT 1 FROM inbox_events completed WHERE completed.event_id = task.event_id)
            """.trimIndent(),
            Long::class.java,
            analysisRunId,
            CITED_PAPER_ACQUISITION_REQUESTED,
        ) ?: 0L
        val pendingIndexingCount = jdbc.queryForObject(
            """
            SELECT count(*)
              FROM outbox_events task
             WHERE task.analysis_run_id = ?
               AND task.event_type = ?
               AND NOT EXISTS (SELECT 1 FROM inbox_events completed WHERE completed.event_id = task.event_id)
            """.trimIndent(),
            Long::class.java,
            analysisRunId,
            CITED_PAPER_INDEXING_REQUESTED,
        ) ?: 0L
        if (pendingTaskCount > 0 || pendingAcquisitionCount > 0 || pendingIndexingCount > 0) return@execute false

        val failedReferenceTaskCount = jdbc.queryForObject(
            """
            SELECT count(*)
              FROM outbox_events task
              JOIN inbox_events completed ON completed.event_id = task.event_id
              LEFT JOIN bibliography_entry_resolutions resolution
                ON resolution.analysis_run_id = task.analysis_run_id
               AND resolution.bibliography_entry_id::text = (task.payload -> 'payload' ->> 'bibliographyEntryId')
             WHERE task.analysis_run_id = ?
               AND task.event_type = ?
               AND resolution.bibliography_entry_id IS NULL
            """.trimIndent(),
            Long::class.java,
            analysisRunId,
            REFERENCE_RESOLUTION_REQUESTED,
        ) ?: 0L

        val failedAcquisitionTaskCount = jdbc.queryForObject(
            """
            SELECT count(*)
              FROM outbox_events task
              JOIN inbox_events completed ON completed.event_id = task.event_id
              LEFT JOIN cited_paper_access access
                ON access.analysis_run_id = task.analysis_run_id
               AND access.bibliography_entry_id::text = (task.payload -> 'payload' ->> 'bibliographyEntryId')
             WHERE task.analysis_run_id = ?
               AND task.event_type = ?
               AND access.bibliography_entry_id IS NULL
            """.trimIndent(),
            Long::class.java,
            analysisRunId,
            CITED_PAPER_ACQUISITION_REQUESTED,
        ) ?: 0L
        val failedEvidenceIndexingCount = jdbc.queryForObject(
            "SELECT count(*) FROM cited_paper_indexing WHERE analysis_run_id = ? AND status = 'FAILED'",
            Long::class.java,
            analysisRunId,
        ) ?: 0L
        val failedTaskCount = failedReferenceTaskCount + failedAcquisitionTaskCount + failedEvidenceIndexingCount
        val parsed = parsedDocumentRepository.find(analysisRunId)
            ?: throw IllegalStateException("Persisted parsed document structure is missing after reference processing.")
        val resolutionSummary = referenceResolutionService.summary(analysisRunId)
        val verificationPipelineConfigured = jdbc.queryForObject(
            "SELECT analysis_run_has_conflict_aware_evidence_coverage(configuration_snapshot) FROM analysis_runs WHERE id = ?",
            Boolean::class.java,
            analysisRunId,
        ) == true
        val systemOneProviderId = jdbc.queryForObject(
            "SELECT configuration_snapshot #>> '{systemOne,provider}' FROM analysis_runs WHERE id = ?",
            String::class.java,
            analysisRunId,
        ) ?: "mock"
        val systemOneAggregationMode = jdbc.queryForObject(
            """
            SELECT CASE
                     WHEN configuration_snapshot #>> '{systemOne,provider}' <> 'mock'
                      AND configuration_snapshot #>> '{aggregation,executionStatus}' = 'NOT_RUN' THEN 'JUDGEMENT_ONLY'
                     WHEN configuration_snapshot #>> '{systemOne,provider}' <> 'mock'
                      AND configuration_snapshot #>> '{aggregation,executionStatus}' = 'PENDING' THEN 'AGGREGATION'
                     ELSE 'NONE'
                   END
              FROM analysis_runs
             WHERE id = ?
            """.trimIndent(),
            String::class.java,
            analysisRunId,
        ) ?: "NONE"
        val systemOneEvaluationOnly = systemOneAggregationMode == "JUDGEMENT_ONLY"
        val systemOneAggregation = systemOneAggregationMode == "AGGREGATION"
        val systemOneJudgementCount = if (systemOneEvaluationOnly || systemOneAggregation) {
            jdbc.queryForObject(
                "SELECT count(*) FROM evidence_judgements WHERE analysis_run_id = ? AND system_one_provider = ?",
                Long::class.java,
                analysisRunId,
                systemOneProviderId,
            ) ?: 0L
        } else {
            0L
        }
        val layaSpanCounts = if (
            systemOneProviderId == LayaSystemOneSettings.PROVIDER_ID &&
            (systemOneEvaluationOnly || systemOneAggregation)
        ) {
            jdbc.queryForObject(
                """
                SELECT count(*) FILTER (WHERE status = 'COMPLETED') AS completed,
                       count(*) FILTER (WHERE status IN ('PENDING', 'FAILED', 'INCOMPLETE')) AS incomplete,
                       count(DISTINCT verification_id) AS verifications
                  FROM laya_evidence_passage_spans
                 WHERE analysis_run_id = ? AND system_one_provider = ?
                """.trimIndent(),
                { rs, _ -> LayaSpanCounts(rs.getLong("completed"), rs.getLong("incomplete"), rs.getLong("verifications")) },
                analysisRunId,
                LayaSystemOneSettings.PROVIDER_ID,
            ) ?: LayaSpanCounts(0, 0, 0)
        } else {
            LayaSpanCounts(0, 0, 0)
        }
        val systemOneDisplayName = when (systemOneProviderId) {
            LayaSystemOneSettings.PROVIDER_ID -> "Laya"
            JevSystemOneSettings.PROVIDER_ID -> "Jev"
            else -> "System One"
        }
        if (verificationPipelineConfigured) markOrphanedPendingVerifications(analysisRunId)
        val verificationCounts = jdbc.queryForObject(
            """
            SELECT count(*)::integer AS total,
                   count(*) FILTER (WHERE processing_status = 'COMPLETED')::integer AS completed,
                   count(*) FILTER (WHERE processing_status <> 'COMPLETED')::integer AS incomplete,
                   count(*) FILTER (WHERE verification_scope = 'FULL_TEXT')::integer AS full_text_pairs,
                   count(*) FILTER (WHERE aggregator_version IS NOT NULL)::integer AS aggregated_pairs
              FROM claim_paper_verifications WHERE analysis_run_id = ?
            """.trimIndent(),
            { rs, _ ->
                VerificationCounts(
                    total = rs.getInt("total"),
                    completed = rs.getInt("completed"),
                    incomplete = rs.getInt("incomplete"),
                    fullTextPairs = rs.getInt("full_text_pairs"),
                    aggregatedPairs = rs.getInt("aggregated_pairs"),
                )
            },
            analysisRunId,
        ) ?: VerificationCounts(0, 0, 0, 0, 0)
        val evaluationFailureCounts = if (systemOneEvaluationOnly || systemOneAggregation) {
            jdbc.queryForObject(
                """
                SELECT count(*) FILTER (WHERE processing_status = 'FAILED') AS failed_pair_count,
                       count(*) FILTER (
                           WHERE processing_status = 'FAILED'
                             AND left(processing_failure_reason, 11) = 'SYSTEM_ONE_'
                       ) AS failed_system_one_count
                  FROM claim_paper_verifications
                 WHERE analysis_run_id = ?
                """.trimIndent(),
                { rs, _ ->
                    EvaluationFailureCounts(
                        failedPairCount = rs.getLong("failed_pair_count"),
                        failedSystemOneCount = rs.getLong("failed_system_one_count"),
                    )
                },
                analysisRunId,
            ) ?: EvaluationFailureCounts(0L, 0L)
        } else {
            EvaluationFailureCounts(0L, 0L)
        }
        val failedEvaluationPairCount = evaluationFailureCounts.failedPairCount
        val failedSystemOneEvaluationCount = evaluationFailureCounts.failedSystemOneCount
        val atomicClaims = parsed.citationContexts.flatMap { it.atomicClaims }
        val inferredClaimTargetLinkCount = atomicClaims.sumOf { it.citationTargets.size }
        val warning = failedTaskCount > 0 ||
            (verificationPipelineConfigured && verificationCounts.incomplete > 0) ||
            (systemOneEvaluationOnly && failedEvaluationPairCount > 0) ||
            ((systemOneEvaluationOnly || systemOneAggregation) && layaSpanCounts.incomplete > 0)
        val finalStatus = when {
            verificationPipelineConfigured && warning -> "COMPLETED_WITH_WARNINGS"
            verificationPipelineConfigured -> "COMPLETED"
            warning -> "COMPLETED_WITH_WARNINGS"
            else -> "PARSED"
        }
        val progressMessage = when {
            systemOneAggregation && warning && layaSpanCounts.verifications > 0 -> "$systemOneDisplayName aggregation used $systemOneJudgementCount uncalibrated Evidence Judgement(s) and ${layaSpanCounts.completed} diagnostic span judgement(s). Laya V1 span diagnostics for ${layaSpanCounts.verifications} Claim–Reference pair(s) are not rolled up into parent or final statuses; ${verificationCounts.incomplete} pair(s) remain incomplete. Results remain uncalibrated."
            systemOneAggregation && verificationCounts.fullTextPairs == 0 && warning -> "Analysis Run completed with warnings for ${verificationCounts.total} Claim–Reference Verification pair(s). No eligible full-text evidence was available, so $systemOneDisplayName assessment and evidence aggregation were not run; ${verificationCounts.incomplete} pair(s) remain incomplete. Results remain uncalibrated and have no attached Human Review."
            systemOneAggregation && verificationCounts.fullTextPairs == 0 -> "Analysis Run completed with terminal outcomes for ${verificationCounts.total} Claim–Reference Verification pair(s). No eligible full-text evidence was available, so $systemOneDisplayName assessment and evidence aggregation were not run. Results remain uncalibrated and have no attached Human Review."
            systemOneAggregation && warning -> "$systemOneDisplayName aggregation used $systemOneJudgementCount uncalibrated Evidence Judgement(s) and experimental thresholds, but ${verificationCounts.incomplete} Claim–Reference Verification pair(s) are incomplete. Results remain uncalibrated."
            systemOneAggregation -> "$systemOneDisplayName aggregation completed for ${verificationCounts.aggregatedPairs} full-text Claim–Reference Verification pair(s) using $systemOneJudgementCount uncalibrated Evidence Judgement(s) and experimental thresholds. Results remain uncalibrated and have no attached Human Review."
            verificationPipelineConfigured && warning -> "The Evidence Coverage Report is ready, but ${verificationCounts.incomplete} Claim–Reference Verification pair(s) are incomplete after processing failures."
            verificationPipelineConfigured -> "The Evidence Coverage Report is complete for ${verificationCounts.total} Claim–Reference Verification pair(s)."
            systemOneEvaluationOnly && warning && layaSpanCounts.incomplete > 0 -> "$systemOneDisplayName produced $systemOneJudgementCount uncalibrated Evidence Judgement(s) and ${layaSpanCounts.completed} diagnostic span judgement(s), but ${layaSpanCounts.incomplete} required Laya span(s) are missing or incomplete; final Claim–Paper Verification remains NOT_RUN."
            systemOneEvaluationOnly && warning && failedSystemOneEvaluationCount > 0 && systemOneJudgementCount > 0 -> "$systemOneDisplayName produced $systemOneJudgementCount uncalibrated Evidence Judgement(s), but $failedSystemOneEvaluationCount Claim–Reference pair(s) could not be judged; final Claim–Paper Verification remains NOT_RUN."
            systemOneEvaluationOnly && warning && failedSystemOneEvaluationCount > 0 -> "$systemOneDisplayName evaluation left $failedSystemOneEvaluationCount Claim–Reference pair(s) unjudged after System One assessment failed; final Claim–Paper Verification remains NOT_RUN."
            systemOneEvaluationOnly && warning && failedEvaluationPairCount > 0 && systemOneJudgementCount > 0 -> "$systemOneDisplayName produced $systemOneJudgementCount uncalibrated Evidence Judgement(s), but $failedEvaluationPairCount Claim–Reference pair(s) could not be assessed because reference or evidence processing failed; final Claim–Paper Verification remains NOT_RUN."
            systemOneEvaluationOnly && warning && failedEvaluationPairCount > 0 -> "$systemOneDisplayName could not assess $failedEvaluationPairCount Claim–Reference pair(s) because reference or evidence processing failed; final Claim–Paper Verification remains NOT_RUN."
            systemOneEvaluationOnly && warning && systemOneJudgementCount > 0 -> "$systemOneDisplayName produced $systemOneJudgementCount uncalibrated Evidence Judgement(s), but $failedTaskCount pipeline task(s) failed; final Claim–Paper Verification remains NOT_RUN."
            systemOneEvaluationOnly && warning -> "$systemOneDisplayName evaluation was incomplete because $failedTaskCount pipeline task(s) failed; final Claim–Paper Verification remains NOT_RUN."
            warning -> "Parsed structure is ready, but $failedTaskCount reference-resolution, cited-paper access, or Evidence Passage indexing task(s) failed; semantic verification has not run."
            systemOneEvaluationOnly && (systemOneJudgementCount > 0 || layaSpanCounts.completed > 0) -> "$systemOneDisplayName produced $systemOneJudgementCount uncalibrated Evidence Judgement(s) and ${layaSpanCounts.completed} diagnostic span judgement(s); final Claim–Paper Verification remains NOT_RUN."
            systemOneEvaluationOnly -> "$systemOneDisplayName is selected for assessment, but no eligible full-text Evidence Passage was available; no Evidence Judgement was produced and final verification remains NOT_RUN."
            else -> "Parsed structure and reference resolution are ready; semantic verification was not configured for this Analysis Run."
        }
        val updated = jdbc.update(
            """
            UPDATE analysis_runs
               SET status = ?,
                   progress = jsonb_build_object(
                       'stage', ?,
                       'percent', 100,
                       'message', ?,
                       'sectionCount', ?,
                       'citationContextCount', ?,
                       'citationOccurrenceCount', ?,
                       'bibliographyEntryCount', ?,
                       'atomicClaimCount', ?,
                       'inferredClaimTargetLinkCount', ?,
                       'resolvedReferenceCount', ?,
                       'unresolvedReferenceCount', ?,
                       'unsupportedReferenceTypeCount', ?,
                       'notAttemptedReferenceCount', ?,
                       'failedReferenceResolutionCount', ?,
                       'acquiredCitedPaperCount', ?,
                       'failedCitedPaperAcquisitionCount', ?,
                       'indexedCitedPaperCount', ?,
                       'failedEvidenceIndexingCount', ?,
                       'totalVerifications', ?,
                       'completedVerifications', ?,
                       'incompleteVerifications', ?
                   ),
                   failure_reason = CASE WHEN ? THEN ? ELSE NULL END,
                   completed_at = CASE WHEN ? THEN now() ELSE completed_at END,
                   updated_at = now()
             WHERE id = ? AND status = 'PROCESSING'
            """.trimIndent(),
            finalStatus,
            finalStatus,
            progressMessage,
            parsed.sections.size,
            parsed.citationContexts.size,
            parsed.citationContexts.sumOf { it.occurrences.size },
            parsed.bibliographyEntries.size,
            atomicClaims.size,
            inferredClaimTargetLinkCount,
            resolutionSummary.resolved,
            resolutionSummary.unresolved,
            resolutionSummary.unsupportedReferenceType,
            resolutionSummary.notAttempted,
            resolutionSummary.failed,
            jdbc.queryForObject("SELECT count(*) FROM cited_paper_access WHERE analysis_run_id = ? AND access_status = 'FULL_TEXT_AVAILABLE'", Int::class.java, analysisRunId) ?: 0,
            failedAcquisitionTaskCount,
            jdbc.queryForObject("SELECT count(*) FROM cited_paper_indexing WHERE analysis_run_id = ? AND status = 'COMPLETED'", Int::class.java, analysisRunId) ?: 0,
            failedEvidenceIndexingCount,
            verificationCounts.total,
            verificationCounts.completed,
            verificationCounts.incomplete,
            warning,
            if (verificationCounts.incomplete > 0) {
                "One or more Claim–Reference Verification pairs are incomplete because processing did not finish."
            } else {
                "One or more reference-resolution, cited-paper access, or Evidence Passage indexing tasks failed."
            },
            verificationPipelineConfigured || warning,
            analysisRunId,
        )
        if (updated != 1) throw IllegalStateException("Analysis Run could not complete its parsed stage.")
        logger.atInfo()
            .addKeyValue("analysisRunId", analysisRunId)
            .addKeyValue("runStatus", finalStatus)
            .addKeyValue("systemOneProviderId", systemOneProviderId)
            .addKeyValue("systemOneMode", systemOneAggregationMode)
            .addKeyValue("failedPipelineTaskCount", failedTaskCount)
            .addKeyValue("resolvedReferenceCount", resolutionSummary.resolved)
            .addKeyValue("unresolvedReferenceCount", resolutionSummary.unresolved)
            .addKeyValue("unsupportedReferenceTypeCount", resolutionSummary.unsupportedReferenceType)
            .addKeyValue("failedReferenceResolutionCount", resolutionSummary.failed)
            .addKeyValue("verificationCount", verificationCounts.total)
            .addKeyValue("completedVerificationCount", verificationCounts.completed)
            .addKeyValue("incompleteVerificationCount", verificationCounts.incomplete)
            .addKeyValue("fullTextPairCount", verificationCounts.fullTextPairs)
            .addKeyValue("evidenceJudgementCount", systemOneJudgementCount)
            .addKeyValue("failedEvaluationPairCount", failedEvaluationPairCount)
            .addKeyValue("failedSystemOneEvaluationCount", failedSystemOneEvaluationCount)
            .addKeyValue("warning", warning)
            .log("Analysis Run processing stage completed")
        true
    } ?: false

    private fun markOrphanedPendingVerifications(analysisRunId: UUID) {
        jdbc.update(
            """
            UPDATE claim_paper_verifications verification
               SET processing_status = 'FAILED',
                   processing_failure_reason = CASE
                       WHEN resolution.bibliography_entry_id IS NULL THEN 'REFERENCE_RESOLUTION_INCOMPLETE'
                       WHEN access.bibliography_entry_id IS NULL THEN 'CITED_PAPER_ACCESS_INCOMPLETE'
                       WHEN verification.verification_scope = 'FULL_TEXT' AND indexing.status IS NULL THEN 'EVIDENCE_INDEXING_INCOMPLETE'
                       WHEN verification.verification_scope = 'FULL_TEXT' AND indexing.status = 'COMPLETED' THEN 'SYSTEM_ONE_INCOMPLETE'
                       WHEN indexing.status = 'FAILED' THEN 'INDEXING_RETRIES_EXHAUSTED'
                       ELSE 'VERIFICATION_INCOMPLETE'
                   END,
                   updated_at = now()
              FROM bibliography_entries reference
              LEFT JOIN bibliography_entry_resolutions resolution
                ON resolution.analysis_run_id = reference.analysis_run_id
               AND resolution.bibliography_entry_id = reference.id
              LEFT JOIN cited_paper_access access
                ON access.analysis_run_id = reference.analysis_run_id
               AND access.bibliography_entry_id = reference.id
              LEFT JOIN cited_paper_indexing indexing
                ON indexing.analysis_run_id = reference.analysis_run_id
               AND indexing.bibliography_entry_id = reference.id
             WHERE verification.analysis_run_id = ?
               AND reference.analysis_run_id = verification.analysis_run_id
               AND reference.id = verification.bibliography_entry_id
               AND verification.processing_status = 'PENDING'
            """.trimIndent(),
            analysisRunId,
        )
    }

    private val logger = LoggerFactory.getLogger(AnalysisRunStageCompletionService::class.java)

    private data class EvaluationFailureCounts(val failedPairCount: Long, val failedSystemOneCount: Long)

    private data class VerificationCounts(
        val total: Int,
        val completed: Int,
        val incomplete: Int,
        val fullTextPairs: Int,
        val aggregatedPairs: Int,
    )
    private data class LayaSpanCounts(val completed: Long, val incomplete: Long, val verifications: Long)
}
