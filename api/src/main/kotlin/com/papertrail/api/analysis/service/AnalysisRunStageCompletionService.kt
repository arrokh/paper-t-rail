package com.papertrail.api.analysis.service

import com.papertrail.api.citation.parsing.ParsedDocumentRepository
import com.papertrail.api.evidence.queue.CITED_PAPER_INDEXING_REQUESTED
import com.papertrail.api.scholarly.acquisition.queue.CITED_PAPER_ACQUISITION_REQUESTED
import com.papertrail.api.scholarly.references.queue.REFERENCE_RESOLUTION_REQUESTED
import com.papertrail.api.scholarly.references.service.ReferenceResolutionService
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
        val citedPaperAccessConfigured = jdbc.queryForObject(
            "SELECT jsonb_exists(configuration_snapshot, 'openAccess') AND configuration_snapshot #>> '{referenceResolution,executionStatus}' <> 'NOT_RUN' FROM analysis_runs WHERE id = ?",
            Boolean::class.java,
            analysisRunId,
        ) == true
        val atomicClaims = parsed.citationContexts.flatMap { it.atomicClaims }
        val inferredClaimTargetLinkCount = atomicClaims.sumOf { it.citationTargets.size }
        val warning = failedTaskCount > 0
        val finalStatus = if (warning) "COMPLETED_WITH_WARNINGS" else "PARSED"
        val progressMessage = when {
            warning -> "Parsed structure is ready, but $failedTaskCount reference-resolution, cited-paper access, or Evidence Passage indexing task(s) failed; semantic verification has not run."
            citedPaperAccessConfigured -> "Parsed structure, reference resolution, legal cited-paper access, language eligibility, and eligible Evidence Passage retrieval are ready; semantic verification has not run."
            else -> "Parsed structure and reference resolution are ready; cited-paper access and semantic verification were not run for this legacy Analysis Run."
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
                       'failedEvidenceIndexingCount', ?
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
            jdbc.queryForObject("SELECT count(*) FROM cited_paper_access WHERE analysis_run_id = ? AND access_status = 'FULL_TEXT_AVAILABLE'", Int::class.java, analysisRunId) ?: 0,
            failedAcquisitionTaskCount,
            jdbc.queryForObject("SELECT count(*) FROM cited_paper_indexing WHERE analysis_run_id = ? AND status = 'COMPLETED'", Int::class.java, analysisRunId) ?: 0,
            failedEvidenceIndexingCount,
            failedTaskCount,
            "One or more reference-resolution, cited-paper access, or Evidence Passage indexing tasks failed.",
            failedTaskCount,
            analysisRunId,
        )
        if (updated != 1) throw IllegalStateException("Analysis Run could not complete its parsed stage.")
        true
    } ?: false
}
