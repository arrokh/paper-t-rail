package com.papertrail.api.queue

import com.papertrail.api.parsing.ParsedDocumentRepository
import com.papertrail.api.references.service.ReferenceResolutionService
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
        if (pendingTaskCount > 0) return@execute false

        val failedTaskCount = jdbc.queryForObject(
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

        val parsed = parsedDocumentRepository.find(analysisRunId)
            ?: throw IllegalStateException("Persisted parsed document structure is missing after reference processing.")
        val resolutionSummary = referenceResolutionService.summary(analysisRunId)
        val atomicClaims = parsed.citationContexts.flatMap { it.atomicClaims }
        val inferredClaimTargetLinkCount = atomicClaims.sumOf { it.citationTargets.size }
        val warning = failedTaskCount > 0
        val finalStatus = if (warning) "COMPLETED_WITH_WARNINGS" else "PARSED"
        val progressMessage = if (warning) {
            "Parsed structure is ready, but $failedTaskCount bibliography resolution task(s) exhausted retries; evidence verification has not run."
        } else {
            "Parsed structure, Atomic Claims, inferred reference links, and conservative bibliography resolution are ready; evidence verification has not run."
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
                       'failedReferenceResolutionCount', ?
                   ),
                   failure_reason = CASE WHEN ? > 0 THEN ? ELSE NULL END,
                   completed_at = CASE WHEN ? > 0 THEN now() ELSE completed_at END,
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
            failedTaskCount,
            "One or more per-entry bibliography resolution tasks exhausted their retries.",
            failedTaskCount,
            analysisRunId,
        )
        if (updated != 1) throw IllegalStateException("Analysis Run could not complete its parsed stage.")
        true
    } ?: false
}
