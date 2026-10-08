package com.papertrail.api.scholarly.references.queue

import com.papertrail.api.utils.JsonUtil
import com.papertrail.api.analysis.repository.AnalysisRunPipelineProgressRepository
import com.papertrail.api.analysis.repository.AnalysisRunProcessingRepository
import com.papertrail.api.analysis.service.AnalysisRunStageCompletionService
import com.papertrail.api.analysis.execution.service.AnalysisRunExecutionService
import com.papertrail.api.analysis.execution.domain.ExecutionSpanArtifactSpec
import com.papertrail.api.analysis.execution.domain.ExecutionSpanSpec
import com.papertrail.api.analysis.execution.domain.ExecutionOperationId
import com.papertrail.api.document.repository.SourceDocumentRepository
import com.papertrail.api.scholarly.acquisition.events.CITED_PAPER_ACQUISITION_REQUESTED
import com.papertrail.api.scholarly.acquisition.events.CitedPaperAcquisitionRequestedPayload
import com.papertrail.api.infrastructure.messaging.events.PipelineEvent
import com.papertrail.api.infrastructure.messaging.events.W3CTraceContext
import com.papertrail.api.infrastructure.messaging.repository.InboxRepository
import com.papertrail.api.infrastructure.messaging.repository.OutboxRepository
import com.papertrail.api.scholarly.references.events.REFERENCE_RESOLUTION_HANDLER
import com.papertrail.api.scholarly.references.events.REFERENCE_RESOLUTION_REQUESTED
import com.papertrail.api.scholarly.references.events.ReferenceResolutionRequestedPayload
import com.papertrail.api.scholarly.references.repository.ReferenceResolutionRepository
import com.papertrail.api.scholarly.references.service.ReferenceResolutionService
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.util.UUID

@Component
class ReferenceResolutionRequestedHandler(
    private val processingRepository: AnalysisRunProcessingRepository,
    private val sourceDocumentRepository: SourceDocumentRepository,
    private val inboxRepository: InboxRepository,
    private val outboxRepository: OutboxRepository,
    private val referenceResolutionRepository: ReferenceResolutionRepository,
    private val transactionTemplate: TransactionTemplate,
    private val referenceResolutionService: ReferenceResolutionService,
    private val analysisRunStageCompletionService: AnalysisRunStageCompletionService,
    private val pipelineProgressRepository: AnalysisRunPipelineProgressRepository,
    private val executionService: AnalysisRunExecutionService? = null,
) {
    fun isProcessed(eventId: UUID): Boolean = inboxRepository.isProcessed(eventId)

    fun handle(serializedEvent: String): UUID {
        val event: PipelineEvent<ReferenceResolutionRequestedPayload> = JsonUtil.fromJson(serializedEvent)
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
        if (sourceDocumentRepository.isDeleted(event.payload.documentId)) return event.eventId
        if (isProcessed(event.eventId)) return event.eventId

        val run = processingRepository.queueRun(event.analysisRunId)
            ?: throw IllegalStateException("Queued Analysis Run is missing.")

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
        if (sourceDocumentRepository.isDeleted(event.payload.documentId)) return
        recordProcessed(event, reason)
        executionService?.finishIfTerminal(event.analysisRunId)
    }

    private fun recordProcessed(
        event: PipelineEvent<ReferenceResolutionRequestedPayload>,
        failureReason: String? = null,
    ) {
        transactionTemplate.executeWithoutResult {
            val inserted = inboxRepository.insertIfAbsent(
                event.eventId,
                event.analysisRunId,
                REFERENCE_RESOLUTION_HANDLER,
                Instant.now(),
            )
            if (!inserted) return@executeWithoutResult
            if (failureReason != null) {
                referenceResolutionService.failResolution(
                    event.analysisRunId,
                    event.payload.bibliographyEntryId,
                    REFERENCE_RESOLUTION_RETRIES_EXHAUSTED,
                )
                processingRepository.updateFailureReasonIfProcessing(event.analysisRunId, failureReason)
                pipelineProgressRepository.markBibliographyItem(
                    event.analysisRunId, "references", "resolve-entry", event.payload.bibliographyEntryId,
                    "FAILED", REFERENCE_RESOLUTION_RETRIES_EXHAUSTED,
                )
                skipDownstream(event, REFERENCE_RESOLUTION_RETRIES_EXHAUSTED)
            } else {
                pipelineProgressRepository.markBibliographyItem(event.analysisRunId, "references", "resolve-entry", event.payload.bibliographyEntryId, "COMPLETED")
                if (!enqueueAcquisitionIfResolved(event)) skipDownstream(event, accessSkipReason(event))
            }
            analysisRunStageCompletionService.completeParsedStageIfReady(event.analysisRunId)
        }
    }

    private fun enqueueAcquisitionIfResolved(event: PipelineEvent<ReferenceResolutionRequestedPayload>): Boolean {
        val accessConfigured = processingRepository.accessConfigured(event.analysisRunId)
        if (!accessConfigured) return false
        val resolved = referenceResolutionRepository.isResolved(event.analysisRunId, event.payload.bibliographyEntryId)
        if (!resolved) return false
        val alreadyQueued = referenceResolutionRepository.hasOutboxRequest(
            event.analysisRunId,
            CITED_PAPER_ACQUISITION_REQUESTED,
            event.payload.bibliographyEntryId,
        )
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
        outboxRepository.insert(acquisitionEvent)
        return true
    }

    private fun accessSkipReason(event: PipelineEvent<ReferenceResolutionRequestedPayload>): String {
        if (!processingRepository.accessConfigured(event.analysisRunId)) return "ACCESS_PATH_NOT_CONFIGURED"
        return when (referenceResolutionRepository.resolutionStatus(event.analysisRunId, event.payload.bibliographyEntryId)) {
            "UNRESOLVED" -> "ACCESS_SKIPPED_IDENTITY_UNRESOLVED"
            "UNSUPPORTED_REFERENCE_TYPE" -> "ACCESS_SKIPPED_UNSUPPORTED_REFERENCE_TYPE"
            else -> "REFERENCE_NOT_ELIGIBLE_FOR_ACCESS"
        }
    }

    private fun skipDownstream(event: PipelineEvent<ReferenceResolutionRequestedPayload>, reason: String) {
        val markAccessSkipped = {
            pipelineProgressRepository.markBibliographyItem(
                event.analysisRunId, "access", "acquire-source", event.payload.bibliographyEntryId, "SKIPPED", reason,
            )
        }
        if (executionService == null) {
            markAccessSkipped()
        } else {
            executionService.recordWithStatus(
                analysisRunId = event.analysisRunId,
                spec = ExecutionSpanSpec(
                    stageId = "access",
                    kind = "INTERNAL",
                    name = "Skip Cited Paper access",
                    attempt = event.attempt + 1,
                    eventId = event.eventId,
                    attributes = mapOf("reasonCode" to reason),
                    operationId = ExecutionOperationId.forEvent(event.eventId, "access-skip"),
                ),
                operation = markAccessSkipped,
                successStatus = { "SKIPPED" },
            )
        }
        pipelineProgressRepository.markBibliographyItem(event.analysisRunId, "evidence", "prepare-evidence", event.payload.bibliographyEntryId, "SKIPPED", reason)
        pipelineProgressRepository.markBibliographyItem(event.analysisRunId, "verification", "assess-and-aggregate", event.payload.bibliographyEntryId, "SKIPPED", reason)
    }

    companion object {
        const val REFERENCE_RESOLUTION_RETRIES_EXHAUSTED = "REFERENCE_RESOLUTION_RETRIES_EXHAUSTED"
    }

}
