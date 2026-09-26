package com.papertrail.api.scholarly.references.queue

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.papertrail.api.analysis.service.AnalysisRunStageCompletionService
import com.papertrail.api.scholarly.acquisition.queue.CITED_PAPER_ACQUISITION_REQUESTED
import com.papertrail.api.scholarly.acquisition.queue.CitedPaperAcquisitionRequestedPayload
import com.papertrail.api.infrastructure.messaging.events.PipelineEvent
import com.papertrail.api.scholarly.references.service.ReferenceResolutionService
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@Component
class ReferenceResolutionRequestedHandler(
    private val jdbc: JdbcTemplate,
    private val transactionTemplate: TransactionTemplate,
    private val objectMapper: ObjectMapper,
    private val referenceResolutionService: ReferenceResolutionService,
    private val analysisRunStageCompletionService: AnalysisRunStageCompletionService,
) {
    fun isProcessed(eventId: UUID): Boolean = jdbc.queryForObject(
        "SELECT EXISTS (SELECT 1 FROM inbox_events WHERE event_id = ?)",
        Boolean::class.java,
        eventId,
    ) == true

    fun handle(serializedEvent: String): UUID {
        val event: PipelineEvent<ReferenceResolutionRequestedPayload> = objectMapper.readValue(serializedEvent)
        require(event.eventType == REFERENCE_RESOLUTION_REQUESTED) { "Unsupported event type '${event.eventType}'." }
        if (isProcessed(event.eventId)) return event.eventId

        val run = jdbc.query(
            "SELECT document_id, source_content_sha256, status FROM analysis_runs WHERE id = ?",
            { rs, _ ->
                RunProvenance(
                    documentId = rs.getObject("document_id", UUID::class.java),
                    sourceHash = rs.getString("source_content_sha256"),
                    status = rs.getString("status"),
                )
            },
            event.analysisRunId,
        ).firstOrNull() ?: throw IllegalStateException("Queued Analysis Run is missing.")

        if (run.documentId != event.payload.documentId || run.sourceHash != event.payload.sourceContentSha256) {
            throw IllegalStateException("Reference-resolution event provenance does not match its Analysis Run.")
        }
        if (run.status != "PROCESSING") {
            throw IllegalStateException("Analysis Run is not accepting reference-resolution work.")
        }

        referenceResolutionService.resolveEntry(event.analysisRunId, event.payload.bibliographyEntryId)
        recordProcessed(event)
        return event.eventId
    }

    fun markFailed(event: PipelineEvent<ReferenceResolutionRequestedPayload>, reason: String) {
        require(event.eventType == REFERENCE_RESOLUTION_REQUESTED) { "Unsupported event type '${event.eventType}'." }
        recordProcessed(event, reason)
    }

    private fun recordProcessed(
        event: PipelineEvent<ReferenceResolutionRequestedPayload>,
        failureReason: String? = null,
    ) {
        transactionTemplate.executeWithoutResult {
            val inserted = jdbc.update(
                "INSERT INTO inbox_events (event_id, handler_name, processed_at) VALUES (?, ?, ?) ON CONFLICT (event_id) DO NOTHING",
                event.eventId,
                REFERENCE_RESOLUTION_HANDLER,
                Timestamp.from(Instant.now()),
            )
            if (inserted == 0) return@executeWithoutResult
            if (failureReason != null) {
                jdbc.update(
                    "UPDATE analysis_runs SET failure_reason = COALESCE(failure_reason, ?), updated_at = now() WHERE id = ? AND status = 'PROCESSING'",
                    failureReason,
                    event.analysisRunId,
                )
            } else {
                enqueueAcquisitionIfResolved(event)
            }
            analysisRunStageCompletionService.completeParsedStageIfReady(event.analysisRunId)
        }
    }

    private fun enqueueAcquisitionIfResolved(event: PipelineEvent<ReferenceResolutionRequestedPayload>) {
        val accessConfigured = jdbc.queryForObject(
            "SELECT jsonb_exists(configuration_snapshot, 'openAccess') FROM analysis_runs WHERE id = ?",
            Boolean::class.java,
            event.analysisRunId,
        ) == true
        if (!accessConfigured) return
        val resolved = jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM bibliography_entry_resolutions WHERE analysis_run_id = ? AND bibliography_entry_id = ? AND status = 'RESOLVED')",
            Boolean::class.java,
            event.analysisRunId,
            event.payload.bibliographyEntryId,
        ) == true
        if (!resolved) return
        val alreadyQueued = jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM outbox_events WHERE analysis_run_id = ? AND event_type = ? AND payload -> 'payload' ->> 'bibliographyEntryId' = ?)",
            Boolean::class.java,
            event.analysisRunId,
            CITED_PAPER_ACQUISITION_REQUESTED,
            event.payload.bibliographyEntryId.toString(),
        ) == true
        if (alreadyQueued) return

        val acquisitionEvent = PipelineEvent(
            eventId = UUID.randomUUID(),
            eventType = CITED_PAPER_ACQUISITION_REQUESTED,
            schemaVersion = 1,
            analysisRunId = event.analysisRunId,
            correlationId = event.correlationId,
            causationId = event.eventId,
            occurredAt = Instant.now(),
            attempt = 0,
            payload = CitedPaperAcquisitionRequestedPayload(
                documentId = event.payload.documentId,
                sourceContentSha256 = event.payload.sourceContentSha256,
                bibliographyEntryId = event.payload.bibliographyEntryId,
            ),
        )
        jdbc.update(
            """
            INSERT INTO outbox_events (
                event_id, event_type, schema_version, analysis_run_id,
                correlation_id, causation_id, occurred_at, payload, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
            """.trimIndent(),
            acquisitionEvent.eventId,
            acquisitionEvent.eventType,
            acquisitionEvent.schemaVersion,
            acquisitionEvent.analysisRunId,
            acquisitionEvent.correlationId,
            acquisitionEvent.causationId,
            Timestamp.from(acquisitionEvent.occurredAt),
            objectMapper.writeValueAsString(acquisitionEvent),
            Timestamp.from(Instant.now()),
        )
    }

    private data class RunProvenance(
        val documentId: UUID,
        val sourceHash: String,
        val status: String,
    )
}
