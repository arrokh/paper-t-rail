package com.papertrail.api.analysis.repository

import com.papertrail.api.external.laya.LayaSystemOneSettings
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class AnalysisRunStageCompletionRepository(
    private val jdbc: JdbcTemplate,
) {
    fun lockRunStatus(analysisRunId: UUID): String? = jdbc.query(
        "SELECT status FROM analysis_runs WHERE id = ? FOR UPDATE",
        { rs, _ -> rs.getString("status") },
        analysisRunId,
    ).firstOrNull()

    fun pendingTaskCount(analysisRunId: UUID, eventType: String): Long = jdbc.queryForObject(
        """
        SELECT count(*)
          FROM outbox_events task
         WHERE task.analysis_run_id = ?
           AND task.event_type = ?
           AND NOT EXISTS (SELECT 1 FROM inbox_events completed WHERE completed.event_id = task.event_id)
        """.trimIndent(),
        Long::class.java,
        analysisRunId,
        eventType,
    ) ?: 0L

    fun failedReferenceTaskCount(analysisRunId: UUID, eventType: String): Long = jdbc.queryForObject(
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
        eventType,
    ) ?: 0L

    fun failedAcquisitionTaskCount(analysisRunId: UUID, eventType: String): Long = jdbc.queryForObject(
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
        eventType,
    ) ?: 0L

    fun failedEvidenceIndexingCount(analysisRunId: UUID): Long = jdbc.queryForObject(
        "SELECT count(*) FROM cited_paper_indexing WHERE analysis_run_id = ? AND status = 'FAILED'",
        Long::class.java,
        analysisRunId,
    ) ?: 0L

    fun verificationPipelineConfigured(analysisRunId: UUID): Boolean = jdbc.queryForObject(
        "SELECT analysis_run_has_conflict_aware_evidence_coverage(configuration_snapshot) FROM analysis_runs WHERE id = ?",
        Boolean::class.java,
        analysisRunId,
    ) == true

    fun systemOneProviderId(analysisRunId: UUID): String = jdbc.queryForObject(
        "SELECT configuration_snapshot #>> '{systemOne,provider}' FROM analysis_runs WHERE id = ?",
        String::class.java,
        analysisRunId,
    ) ?: "mock"

    fun systemOneAggregationMode(analysisRunId: UUID): String = jdbc.queryForObject(
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

    fun systemOneJudgementCount(analysisRunId: UUID, providerId: String): Long = jdbc.queryForObject(
        "SELECT count(*) FROM evidence_judgements WHERE analysis_run_id = ? AND system_one_provider = ?",
        Long::class.java,
        analysisRunId,
        providerId,
    ) ?: 0L

    fun layaSpanCounts(analysisRunId: UUID): LayaSpanCounts = jdbc.queryForObject(
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

    fun markOrphanedPendingVerifications(analysisRunId: UUID) {
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

    fun verificationCounts(analysisRunId: UUID): VerificationCounts = jdbc.queryForObject(
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

    fun evaluationFailureCounts(analysisRunId: UUID): EvaluationFailureCounts = jdbc.queryForObject(
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

    fun fullTextAccessCount(analysisRunId: UUID): Int = jdbc.queryForObject(
        "SELECT count(*) FROM cited_paper_access WHERE analysis_run_id = ? AND access_status = 'FULL_TEXT_AVAILABLE'",
        Int::class.java,
        analysisRunId,
    ) ?: 0

    fun completedIndexingCount(analysisRunId: UUID): Int = jdbc.queryForObject(
        "SELECT count(*) FROM cited_paper_indexing WHERE analysis_run_id = ? AND status = 'COMPLETED'",
        Int::class.java,
        analysisRunId,
    ) ?: 0

    fun updateCompletion(analysisRunId: UUID, completion: CompletionUpdate): Boolean = jdbc.update(
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
        completion.finalStatus,
        completion.finalStatus,
        completion.progressMessage,
        completion.sectionCount,
        completion.citationContextCount,
        completion.citationOccurrenceCount,
        completion.bibliographyEntryCount,
        completion.atomicClaimCount,
        completion.inferredClaimTargetLinkCount,
        completion.resolvedReferenceCount,
        completion.unresolvedReferenceCount,
        completion.unsupportedReferenceTypeCount,
        completion.notAttemptedReferenceCount,
        completion.failedReferenceResolutionCount,
        completion.acquiredCitedPaperCount,
        completion.failedCitedPaperAcquisitionCount,
        completion.indexedCitedPaperCount,
        completion.failedEvidenceIndexingCount,
        completion.totalVerifications,
        completion.completedVerifications,
        completion.incompleteVerifications,
        completion.warning,
        completion.failureReason,
        completion.markCompleted,
        analysisRunId,
    ) == 1

    data class LayaSpanCounts(val completed: Long, val incomplete: Long, val verifications: Long)
    data class VerificationCounts(
        val total: Int,
        val completed: Int,
        val incomplete: Int,
        val fullTextPairs: Int,
        val aggregatedPairs: Int,
    )
    data class EvaluationFailureCounts(val failedPairCount: Long, val failedSystemOneCount: Long)

    data class CompletionUpdate(
        val finalStatus: String,
        val progressMessage: String,
        val sectionCount: Int,
        val citationContextCount: Int,
        val citationOccurrenceCount: Int,
        val bibliographyEntryCount: Int,
        val atomicClaimCount: Int,
        val inferredClaimTargetLinkCount: Int,
        val resolvedReferenceCount: Int,
        val unresolvedReferenceCount: Int,
        val unsupportedReferenceTypeCount: Int,
        val notAttemptedReferenceCount: Int,
        val failedReferenceResolutionCount: Int,
        val acquiredCitedPaperCount: Int,
        val failedCitedPaperAcquisitionCount: Long,
        val indexedCitedPaperCount: Int,
        val failedEvidenceIndexingCount: Long,
        val totalVerifications: Int,
        val completedVerifications: Int,
        val incompleteVerifications: Int,
        val warning: Boolean,
        val failureReason: String,
        val markCompleted: Boolean,
    )
}
