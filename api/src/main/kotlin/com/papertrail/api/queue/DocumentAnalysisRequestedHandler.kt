package com.papertrail.api.queue

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.papertrail.api.documents.sha256Hex
import com.papertrail.api.storage.SourceDocumentObjectStore
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.util.UUID

@Component
class DocumentAnalysisRequestedHandler(
    private val jdbc: JdbcTemplate,
    private val transactionTemplate: TransactionTemplate,
    private val objectMapper: ObjectMapper,
    private val objectStore: SourceDocumentObjectStore,
) {
    fun isProcessed(eventId: UUID): Boolean = jdbc.queryForObject(
        "SELECT EXISTS (SELECT 1 FROM inbox_events WHERE event_id = ?)",
        Boolean::class.java,
        eventId,
    ) == true

    fun handle(serializedEvent: String): UUID {
        val event: PipelineEvent<DocumentAnalysisRequestedPayload> = objectMapper.readValue(serializedEvent)
        require(event.eventType == DOCUMENT_ANALYSIS_REQUESTED) { "Unsupported event type '${event.eventType}'." }
        if (isProcessed(event.eventId)) return event.eventId

        val document = jdbc.query(
            "SELECT object_key, sha256 FROM source_documents WHERE id = ?",
            { rs, _ -> StoredSource(rs.getString("object_key"), rs.getString("sha256")) },
            event.payload.documentId,
        ).firstOrNull() ?: throw IllegalStateException("Queued Source Document is missing.")
        val run = jdbc.query(
            "SELECT document_id, source_content_sha256, status FROM analysis_runs WHERE id = ?",
            { rs, _ -> RunProvenance(rs.getObject("document_id", UUID::class.java), rs.getString("source_content_sha256"), rs.getString("status")) },
            event.analysisRunId,
        ).firstOrNull() ?: throw IllegalStateException("Queued Analysis Run is missing.")

        if (run.documentId != event.payload.documentId || run.sourceHash != event.payload.sourceContentSha256 || document.sha256 != run.sourceHash) {
            throw IllegalStateException("Queue event provenance does not match the persisted Source Document and Analysis Run.")
        }
        val content = objectStore.get(document.objectKey)
        if (sha256Hex(content) != run.sourceHash) {
            throw IllegalStateException("Stored Source Document failed its SHA-256 integrity check.")
        }

        transactionTemplate.executeWithoutResult {
            val inserted = jdbc.update(
                "INSERT INTO inbox_events (event_id, handler_name, processed_at) VALUES (?, ?, ?) ON CONFLICT (event_id) DO NOTHING",
                event.eventId,
                DOCUMENT_ANALYSIS_HANDLER,
                java.sql.Timestamp.from(Instant.now()),
            )
            if (inserted == 0) return@executeWithoutResult

            jdbc.update(
                """
                UPDATE analysis_runs
                   SET status = CASE WHEN status = 'QUEUED' THEN 'PROCESSING' ELSE status END,
                       started_at = COALESCE(started_at, now()),
                       progress = '{"stage":"SOURCE_DOCUMENT_VERIFIED","message":"The stored PDF hash was verified by a worker."}'::jsonb,
                       updated_at = now()
                 WHERE id = ? AND document_id = ? AND source_content_sha256 = ?
                   AND status IN ('QUEUED', 'PROCESSING')
                """.trimIndent(),
                event.analysisRunId,
                event.payload.documentId,
                run.sourceHash,
            )
        }
        return event.eventId
    }

    fun markFailed(event: PipelineEvent<DocumentAnalysisRequestedPayload>, reason: String) {
        transactionTemplate.executeWithoutResult {
            jdbc.update(
                """
                UPDATE analysis_runs
                   SET status = 'FAILED',
                       failure_reason = ?,
                       progress = jsonb_build_object('stage', 'FAILED', 'percent', 0, 'message', ?),
                       completed_at = now(),
                       updated_at = now()
                 WHERE id = ? AND document_id = ? AND source_content_sha256 = ?
                   AND status IN ('QUEUED', 'PROCESSING')
                   AND NOT EXISTS (SELECT 1 FROM inbox_events WHERE event_id = ?)
                """.trimIndent(),
                reason,
                reason,
                event.analysisRunId,
                event.payload.documentId,
                event.payload.sourceContentSha256,
                event.eventId,
            )
        }
    }

    private data class StoredSource(val objectKey: String, val sha256: String)
    private data class RunProvenance(val documentId: UUID, val sourceHash: String, val status: String)
}
