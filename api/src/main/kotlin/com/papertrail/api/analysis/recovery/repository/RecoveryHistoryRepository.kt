package com.papertrail.api.analysis.recovery.repository

import com.papertrail.api.analysis.recovery.domain.RecoveryHistoryEvent
import com.papertrail.api.analysis.recovery.domain.RecoveryStagingException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.nio.charset.StandardCharsets
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.Base64
import java.util.UUID

@Repository
class RecoveryHistoryRepository(
    private val jdbc: JdbcTemplate,
) {
    @Transactional
    fun append(
        batchId: UUID,
        analysisRunId: UUID,
        eventType: String,
        outcomeCode: String,
        stageCode: String? = null,
        attemptNumber: Int? = null,
        sourceEventId: UUID? = null,
        bibliographyEntryId: UUID? = null,
        uploadId: UUID? = null,
        previousUploadId: UUID? = null,
        batchStatus: String = "OPEN",
        successorAnalysisRunId: UUID? = null,
        successorStatus: String? = null,
        selectedEntryCount: Int? = null,
        identifierAssistanceEntryCount: Int? = null,
        resolutionQueuedEntryCount: Int? = null,
        createdAt: Instant = Instant.now(),
    ) {
        jdbc.update(
            """
            INSERT INTO recovery_history_events (
                id, batch_id, analysis_run_id, batch_status, event_type, outcome_code, stage_code,
                source_event_id, attempt_number, bibliography_entry_id, upload_id, previous_upload_id,
                successor_analysis_run_id, successor_status, selected_entry_count,
                identifier_assistance_entry_count, resolution_queued_entry_count, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (source_event_id, event_type, stage_code, attempt_number)
                WHERE source_event_id IS NOT NULL DO NOTHING
            """.trimIndent(),
            UUID.randomUUID(), batchId, analysisRunId, batchStatus, eventType, outcomeCode, stageCode,
            sourceEventId, attemptNumber, bibliographyEntryId, uploadId, previousUploadId, successorAnalysisRunId,
            successorStatus, selectedEntryCount, identifierAssistanceEntryCount,
            resolutionQueuedEntryCount, Timestamp.from(createdAt),
        )
    }

    @Transactional
    fun appendForSuccessor(
        successorAnalysisRunId: UUID,
        bibliographyEntryId: UUID,
        eventType: String,
        outcomeCode: String,
        stageCode: String,
        attemptNumber: Int?,
        sourceEventId: UUID? = null,
        eventBatchStatus: String = "SUBMITTED",
        createdAt: Instant = Instant.now(),
    ) {
        val lineage = jdbc.query(
            """
            SELECT submission.batch_id, submission.predecessor_analysis_run_id,
                   manifest.predecessor_bibliography_entry_id
              FROM recovery_batch_successor_submissions submission
              JOIN analysis_run_successor_work_manifest manifest
                ON manifest.successor_analysis_run_id = submission.successor_analysis_run_id
             WHERE submission.successor_analysis_run_id = ?
               AND manifest.bibliography_entry_id = ?
            """.trimIndent(),
            { rs, _ -> HistoryLineage(
                batchId = rs.getObject("batch_id", UUID::class.java),
                predecessorRunId = rs.getObject("predecessor_analysis_run_id", UUID::class.java),
                predecessorEntryId = rs.getObject("predecessor_bibliography_entry_id", UUID::class.java),
            ) },
            successorAnalysisRunId,
            bibliographyEntryId,
        ).firstOrNull() ?: return
        append(
            batchId = lineage.batchId,
            analysisRunId = lineage.predecessorRunId,
            eventType = eventType,
            outcomeCode = outcomeCode,
            stageCode = stageCode,
            attemptNumber = attemptNumber,
            sourceEventId = sourceEventId,
            bibliographyEntryId = lineage.predecessorEntryId,
            batchStatus = eventBatchStatus,
            successorAnalysisRunId = successorAnalysisRunId.takeIf { eventType == "SUCCESSOR_SUBMITTED" },
            createdAt = createdAt,
        )
    }

    fun page(analysisRunId: UUID, cursor: String?, limit: Int): RecoveryHistoryPage {
        if (limit !in MIN_PAGE_SIZE..MAX_PAGE_SIZE) {
            throw RecoveryStagingException("RECOVERY_HISTORY_LIMIT_INVALID", 400, "Recovery history page size must be between 1 and 100.")
        }
        val activeRun = jdbc.queryForObject(
            """
            SELECT EXISTS (
                SELECT 1
                  FROM analysis_runs run
                  JOIN source_documents document ON document.id = run.document_id
                  LEFT JOIN source_document_tombstones tombstone ON tombstone.document_id = document.id
                 WHERE run.id = ? AND tombstone.document_id IS NULL
            )
            """.trimIndent(),
            Boolean::class.java,
            analysisRunId,
        ) == true
        if (!activeRun) throw RecoveryStagingException("ANALYSIS_RUN_NOT_FOUND", 404, "Analysis Run not found.")

        val cursorEventId = cursor?.let(::decodeCursor)
        val position = cursorEventId?.let { eventId ->
            jdbc.query(
                "SELECT append_sequence FROM recovery_history_events WHERE analysis_run_id = ? AND id = ?",
                { rs, _ -> rs.getLong("append_sequence") },
                analysisRunId,
                eventId,
            ).firstOrNull() ?: throw invalidCursor()
        }
        val rows = if (position == null) {
            jdbc.query(
                """
                SELECT * FROM recovery_history_events
                 WHERE analysis_run_id = ?
                 ORDER BY append_sequence DESC
                 LIMIT ?
                """.trimIndent(),
                { rs, _ -> rs.toHistoryRow() },
                analysisRunId,
                limit + 1,
            )
        } else {
            jdbc.query(
                """
                SELECT * FROM recovery_history_events
                 WHERE analysis_run_id = ?
                   AND append_sequence < ?
                 ORDER BY append_sequence DESC
                 LIMIT ?
                """.trimIndent(),
                { rs, _ -> rs.toHistoryRow() },
                analysisRunId,
                position,
                limit + 1,
            )
        }
        val hasMore = rows.size > limit
        val pageRows = rows.take(limit)
        return RecoveryHistoryPage(
            analysisRunId = analysisRunId,
            events = pageRows.map(HistoryRow::event),
            nextCursor = if (hasMore) encodeCursor(pageRows.last().event.id) else null,
        )
    }

    private fun decodeCursor(cursor: String): UUID = try {
        UUID.fromString(String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8))
    } catch (_: Exception) {
        throw invalidCursor()
    }

    private fun encodeCursor(eventId: UUID): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(eventId.toString().toByteArray(StandardCharsets.UTF_8))

    private fun invalidCursor() = RecoveryStagingException(
        "RECOVERY_HISTORY_CURSOR_INVALID",
        400,
        "Recovery history cursor is invalid.",
    )

    private fun ResultSet.toHistoryRow() = HistoryRow(
        event = toEvent(),
        appendSequence = getLong("append_sequence"),
    )

    private fun ResultSet.toEvent() = RecoveryHistoryEvent(
        id = getObject("id", UUID::class.java),
        batchId = getObject("batch_id", UUID::class.java),
        analysisRunId = getObject("analysis_run_id", UUID::class.java),
        batchStatus = getString("batch_status"),
        eventType = getString("event_type"),
        outcomeCode = getString("outcome_code"),
        stageCode = getString("stage_code"),
        attemptNumber = getObject("attempt_number", Integer::class.java)?.toInt(),
        bibliographyEntryId = getObject("bibliography_entry_id", UUID::class.java),
        uploadId = getObject("upload_id", UUID::class.java),
        previousUploadId = getObject("previous_upload_id", UUID::class.java),
        successorAnalysisRunId = getObject("successor_analysis_run_id", UUID::class.java),
        successorStatus = getString("successor_status"),
        selectedEntryCount = getObject("selected_entry_count", Integer::class.java)?.toInt(),
        identifierAssistanceEntryCount = getObject("identifier_assistance_entry_count", Integer::class.java)?.toInt(),
        resolutionQueuedEntryCount = getObject("resolution_queued_entry_count", Integer::class.java)?.toInt(),
        createdAt = getTimestamp("created_at").toInstant(),
    )

    private data class HistoryRow(
        val event: RecoveryHistoryEvent,
        val appendSequence: Long,
    )

    private data class HistoryLineage(
        val batchId: UUID,
        val predecessorRunId: UUID,
        val predecessorEntryId: UUID,
    )

    companion object {
        const val DEFAULT_PAGE_SIZE = 50
        const val MAX_PAGE_SIZE = 100
        private const val MIN_PAGE_SIZE = 1
    }
}
