package com.papertrail.api.scholarly.references.queue

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.papertrail.api.analysis.service.AnalysisRunPipelineProgressRepository
import com.papertrail.api.analysis.service.AnalysisRunStageCompletionService
import com.papertrail.api.analysis.execution.AnalysisRunExecutionService
import com.papertrail.api.analysis.execution.ExecutionSpanArtifactSpec
import com.papertrail.api.analysis.execution.ExecutionSpanSpec
import com.papertrail.api.analysis.execution.ExecutionOperationId
import com.papertrail.api.document.service.isSourceDocumentDeleted
import com.papertrail.api.scholarly.acquisition.queue.CITED_PAPER_ACQUISITION_REQUESTED
import com.papertrail.api.scholarly.acquisition.queue.CitedPaperAcquisitionRequestedPayload
import com.papertrail.api.infrastructure.messaging.events.PipelineEvent
import com.papertrail.api.infrastructure.messaging.events.W3CTraceContext
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
    private val pipelineProgressRepository: AnalysisRunPipelineProgressRepository = AnalysisRunPipelineProgressRepository(jdbc),
    private val executionService: AnalysisRunExecutionService? = null,
) {
    fun isProcessed(eventId: UUID): Boolean = jdbc.queryForObject(
        "SELECT EXISTS (SELECT 1 FROM inbox_events WHERE event_id = ?)",
        Boolean::class.java,
        eventId,
    ) == true

    fun handle(serializedEvent: String): UUID {
        val event: PipelineEvent<ReferenceResolutionRequestedPayload> = objectMapper.readValue(serializedEvent)
        require(event.eventType == REFERENCE_RESOLUTION_REQUESTED) { "Unsupported event type '${event.eventType}'." }
        executionService?.recordQueueIntervals(
            event.analysisRunId, event.eventId, event.attempt, "references", event.queueWaitStartedAt,
            event.retryScheduledAt, event.retryDueAt, event.causationId,
        )
        val operation = { handleEvent(event) }
        return executionService?.record(
            event.analysisRunId,
            ExecutionSpanSpec("references", "QUEUE", "Reference resolution attempt", event.attempt + 1, event.eventId, attributes = mapOf("eventAttempt" to event.attempt), operationId = event.eventId, causationEventId = event.causationId),
            operation,
        ) ?: operation()
    }

    private fun handleEvent(event: PipelineEvent<ReferenceResolutionRequestedPayload>): UUID {
        if (jdbc.isSourceDocumentDeleted(event.payload.documentId)) return event.eventId
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

        pipelineProgressRepository.markBibliographyItem(event.analysisRunId, "references", "resolve-entry", event.payload.bibliographyEntryId, "IN_PROGRESS")
        if (executionService == null) {
            referenceResolutionService.resolveEntry(event.analysisRunId, event.payload.bibliographyEntryId)
        } else {
            executionService.record(
                event.analysisRunId,
                ExecutionSpanSpec("references", "INTERNAL", "Resolve bibliography entry", event.attempt + 1, event.eventId, operationId = ExecutionOperationId.forEvent(event.eventId, "reference-resolution")),
            ) {
                executionService.captureCurrent(
                    ExecutionSpanArtifactSpec("INPUT", "run-stage-input-v1", mapOf("itemCount" to 1)),
                )
                referenceResolutionService.resolveEntry(event.analysisRunId, event.payload.bibliographyEntryId).also {
                    executionService.captureCurrent(
                        ExecutionSpanArtifactSpec("RESULT", "run-stage-result-v1", mapOf("status" to "SUCCEEDED", "itemCount" to 1)),
                    )
                }
            }
        }
        recordProcessed(event)
        return event.eventId
    }

    fun markFailed(event: PipelineEvent<ReferenceResolutionRequestedPayload>, reason: String) {
        require(event.eventType == REFERENCE_RESOLUTION_REQUESTED) { "Unsupported event type '${event.eventType}'." }
        if (jdbc.isSourceDocumentDeleted(event.payload.documentId)) return
        recordProcessed(event, reason)
        executionService?.finishIfTerminal(event.analysisRunId)
    }

    private fun recordProcessed(
        event: PipelineEvent<ReferenceResolutionRequestedPayload>,
        failureReason: String? = null,
    ) {
        transactionTemplate.executeWithoutResult {
            val inserted = jdbc.update(
                "INSERT INTO inbox_events (event_id, analysis_run_id, handler_name, processed_at) VALUES (?, ?, ?, ?) ON CONFLICT (event_id) DO NOTHING",
                event.eventId,
                event.analysisRunId,
                REFERENCE_RESOLUTION_HANDLER,
                Timestamp.from(Instant.now()),
            )
            if (inserted == 0) return@executeWithoutResult
            if (failureReason != null) {
                referenceResolutionService.failResolution(
                    event.analysisRunId,
                    event.payload.bibliographyEntryId,
                    REFERENCE_RESOLUTION_RETRIES_EXHAUSTED,
                )
                jdbc.update(
                    "UPDATE analysis_runs SET failure_reason = COALESCE(failure_reason, ?), updated_at = now() WHERE id = ? AND status = 'PROCESSING'",
                    failureReason,
                    event.analysisRunId,
                )
                pipelineProgressRepository.markBibliographyItem(
                    event.analysisRunId, "references", "resolve-entry", event.payload.bibliographyEntryId,
                    "FAILED", REFERENCE_RESOLUTION_RETRIES_EXHAUSTED,
                )
                skipDownstream(event, REFERENCE_RESOLUTION_RETRIES_EXHAUSTED)
            } else {
                pipelineProgressRepository.markBibliographyItem(event.analysisRunId, "references", "resolve-entry", event.payload.bibliographyEntryId, "COMPLETED")
                if (!enqueueAcquisitionIfResolved(event)) skipDownstream(event, "REFERENCE_NOT_ELIGIBLE_FOR_ACCESS")
            }
            analysisRunStageCompletionService.completeParsedStageIfReady(event.analysisRunId)
        }
    }

    private fun enqueueAcquisitionIfResolved(event: PipelineEvent<ReferenceResolutionRequestedPayload>): Boolean {
        val accessConfigured = jdbc.queryForObject(
            "SELECT jsonb_typeof(configuration_snapshot -> 'openAccess') = 'object' FROM analysis_runs WHERE id = ?",
            Boolean::class.java,
            event.analysisRunId,
        ) == true
        if (!accessConfigured) return false
        val resolved = jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM bibliography_entry_resolutions WHERE analysis_run_id = ? AND bibliography_entry_id = ? AND status = 'RESOLVED')",
            Boolean::class.java,
            event.analysisRunId,
            event.payload.bibliographyEntryId,
        ) == true
        if (!resolved) return false
        val alreadyQueued = jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM outbox_events WHERE analysis_run_id = ? AND event_type = ? AND payload -> 'payload' ->> 'bibliographyEntryId' = ?)",
            Boolean::class.java,
            event.analysisRunId,
            CITED_PAPER_ACQUISITION_REQUESTED,
            event.payload.bibliographyEntryId.toString(),
        ) == true
        if (alreadyQueued) return true

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
            traceparent = W3CTraceContext.child(event.traceparent),
            tracestate = event.tracestate,
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
        return true
    }

    private fun skipDownstream(event: PipelineEvent<ReferenceResolutionRequestedPayload>, reason: String) {
        pipelineProgressRepository.markBibliographyItem(event.analysisRunId, "access", "acquire-source", event.payload.bibliographyEntryId, "SKIPPED", reason)
        pipelineProgressRepository.markBibliographyItem(event.analysisRunId, "evidence", "prepare-evidence", event.payload.bibliographyEntryId, "SKIPPED", reason)
        pipelineProgressRepository.markBibliographyItem(event.analysisRunId, "verification", "assess-and-aggregate", event.payload.bibliographyEntryId, "SKIPPED", reason)
    }

    companion object {
        const val REFERENCE_RESOLUTION_RETRIES_EXHAUSTED = "REFERENCE_RESOLUTION_RETRIES_EXHAUSTED"
    }

    private data class RunProvenance(
        val documentId: UUID,
        val sourceHash: String,
        val status: String,
    )
}
