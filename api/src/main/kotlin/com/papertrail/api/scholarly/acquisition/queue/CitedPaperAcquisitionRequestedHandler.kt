package com.papertrail.api.scholarly.acquisition.queue

import com.papertrail.api.utils.JsonUtil
import com.papertrail.api.analysis.repository.AnalysisRunPipelineProgressRepository
import com.papertrail.api.analysis.repository.AnalysisRunProcessingRepository
import com.papertrail.api.analysis.service.AnalysisRunStageCompletionService
import com.papertrail.api.analysis.execution.service.AnalysisRunExecutionService
import com.papertrail.api.analysis.execution.domain.ExecutionSpanArtifactSpec
import com.papertrail.api.analysis.execution.domain.ExecutionSpanSpec
import com.papertrail.api.analysis.execution.domain.ExecutionOperationId
import com.papertrail.api.document.repository.SourceDocumentRepository
import com.papertrail.api.evidence.queue.CitedPaperIndexingQueue
import com.papertrail.api.evidence.queue.EvidenceIndexingEnqueueResult
import com.papertrail.api.evidence.verification.service.EvidenceVerificationService
import com.papertrail.api.infrastructure.messaging.events.PipelineEvent
import com.papertrail.api.infrastructure.messaging.events.W3CTraceContext
import com.papertrail.api.infrastructure.messaging.repository.InboxRepository
import com.papertrail.api.scholarly.acquisition.events.CITED_PAPER_ACQUISITION_HANDLER
import com.papertrail.api.scholarly.acquisition.events.CITED_PAPER_ACQUISITION_REQUESTED
import com.papertrail.api.scholarly.acquisition.events.CitedPaperAcquisitionRequestedPayload
import com.papertrail.api.scholarly.acquisition.service.CitedPaperAccessService
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.util.UUID

@Component
class CitedPaperAcquisitionRequestedHandler(
    private val processingRepository: AnalysisRunProcessingRepository,
    private val sourceDocumentRepository: SourceDocumentRepository,
    private val inboxRepository: InboxRepository,
    private val transactionTemplate: TransactionTemplate,
    private val citedPaperAccessService: CitedPaperAccessService,
    private val citedPaperIndexingQueue: CitedPaperIndexingQueue,
    private val analysisRunStageCompletionService: AnalysisRunStageCompletionService,
    private val pipelineProgressRepository: AnalysisRunPipelineProgressRepository,
    private val evidenceVerificationService: EvidenceVerificationService? = null,
    private val executionService: AnalysisRunExecutionService? = null,
) {
    fun isProcessed(eventId: UUID): Boolean = inboxRepository.isProcessed(eventId)

    fun handle(serializedEvent: String): UUID {
        val event: PipelineEvent<CitedPaperAcquisitionRequestedPayload> = JsonUtil.fromJson(serializedEvent)
        require(event.eventType == CITED_PAPER_ACQUISITION_REQUESTED) { "Unsupported event type '${event.eventType}'." }
        executionService?.recordQueueIntervals(
            event.analysisRunId, event.eventId, event.attempt, "access", event.queueWaitStartedAt,
            event.retryScheduledAt, event.retryDueAt, event.causationId,
        )
        val operation = { handleEvent(event) }
        return executionService?.record(
            event.analysisRunId,
            ExecutionSpanSpec("access", "QUEUE", "Cited-paper access attempt", event.attempt + 1, event.eventId, attributes = mapOf("eventAttempt" to event.attempt), operationId = event.eventId, causationEventId = event.causationId),
            operation,
        ) ?: operation()
    }

    private fun handleEvent(event: PipelineEvent<CitedPaperAcquisitionRequestedPayload>): UUID {
        if (sourceDocumentRepository.isDeleted(event.payload.documentId)) return event.eventId
        if (isProcessed(event.eventId)) return event.eventId

        val run = processingRepository.queueRun(event.analysisRunId)
            ?: throw IllegalStateException("Queued Analysis Run is missing.")

        if (run.documentId != event.payload.documentId || run.sourceHash != event.payload.sourceContentSha256) {
            throw IllegalStateException("Cited-paper acquisition event provenance does not match its Analysis Run.")
        }
        if (run.status != "PROCESSING") throw IllegalStateException("Analysis Run is not accepting cited-paper access work.")

        pipelineProgressRepository.markBibliographyItem(event.analysisRunId, "access", "acquire-source", event.payload.bibliographyEntryId, "IN_PROGRESS")
        if (executionService == null) {
            citedPaperAccessService.acquire(event.analysisRunId, event.payload.bibliographyEntryId)
        } else {
            executionService.record(
                event.analysisRunId,
                ExecutionSpanSpec("access", "INTERNAL", "Acquire cited-paper source", event.attempt + 1, event.eventId, operationId = ExecutionOperationId.forEvent(event.eventId, "source-acquisition")),
            ) {
                executionService.captureCurrent(
                    ExecutionSpanArtifactSpec("INPUT", "run-stage-input-v1", mapOf("itemCount" to 1)),
                )
                citedPaperAccessService.acquire(event.analysisRunId, event.payload.bibliographyEntryId).also {
                    executionService.captureCurrent(
                        ExecutionSpanArtifactSpec("RESULT", "run-stage-result-v1", mapOf("status" to "SUCCEEDED", "itemCount" to 1)),
                    )
                }
            }
        }
        recordProcessed(event)
        return event.eventId
    }

    fun markFailed(event: PipelineEvent<CitedPaperAcquisitionRequestedPayload>, reason: String) {
        require(event.eventType == CITED_PAPER_ACQUISITION_REQUESTED) { "Unsupported event type '${event.eventType}'." }
        if (sourceDocumentRepository.isDeleted(event.payload.documentId)) return
        recordProcessed(event, reason)
        executionService?.finishIfTerminal(event.analysisRunId)
    }

    private fun recordProcessed(
        event: PipelineEvent<CitedPaperAcquisitionRequestedPayload>,
        failureReason: String? = null,
    ) {
        transactionTemplate.executeWithoutResult {
            val inserted = inboxRepository.insertIfAbsent(
                event.eventId,
                event.analysisRunId,
                CITED_PAPER_ACQUISITION_HANDLER,
                Instant.now(),
            )
            if (!inserted) return@executeWithoutResult
            if (failureReason != null) {
                citedPaperAccessService.failAccess(
                    event.analysisRunId,
                    event.payload.bibliographyEntryId,
                    CITED_PAPER_ACCESS_RETRIES_EXHAUSTED,
                )
                processingRepository.updateFailureReasonIfProcessing(event.analysisRunId, failureReason)
                pipelineProgressRepository.markBibliographyItem(
                    event.analysisRunId, "access", "acquire-source", event.payload.bibliographyEntryId,
                    "FAILED", CITED_PAPER_ACCESS_RETRIES_EXHAUSTED,
                )
                skipEvidence(event, CITED_PAPER_ACCESS_RETRIES_EXHAUSTED)
            } else {
                pipelineProgressRepository.markBibliographyItem(event.analysisRunId, "access", "acquire-source", event.payload.bibliographyEntryId, "COMPLETED")
                val enqueueResult = citedPaperIndexingQueue.enqueueIfEligible(
                    analysisRunId = event.analysisRunId,
                    bibliographyEntryId = event.payload.bibliographyEntryId,
                    documentId = event.payload.documentId,
                    sourceContentSha256 = event.payload.sourceContentSha256,
                    correlationId = event.correlationId,
                    causationId = event.eventId,
                    traceparent = W3CTraceContext.child(event.traceparent),
                    tracestate = event.tracestate,
                )
                when (enqueueResult) {
                    EvidenceIndexingEnqueueResult.QUEUED -> Unit
                    EvidenceIndexingEnqueueResult.NOT_ELIGIBLE -> skipEvidence(event, "NO_ELIGIBLE_FULL_TEXT_ASSET")
                    EvidenceIndexingEnqueueResult.FAILED -> {
                        evidenceVerificationService?.failFullText(
                            event.analysisRunId,
                            event.payload.bibliographyEntryId,
                            "EMBEDDING_PROFILE_UNAVAILABLE",
                        )
                        pipelineProgressRepository.markBibliographyItem(
                            event.analysisRunId, "evidence", "prepare-evidence", event.payload.bibliographyEntryId,
                            "FAILED", "EMBEDDING_PROFILE_UNAVAILABLE",
                        )
                        pipelineProgressRepository.markBibliographyItem(
                            event.analysisRunId, "verification", "assess-and-aggregate", event.payload.bibliographyEntryId,
                            "SKIPPED", "EVIDENCE_PREPARATION_FAILED",
                        )
                    }
                }
            }
            analysisRunStageCompletionService.completeParsedStageIfReady(event.analysisRunId)
        }
    }

    private fun skipEvidence(event: PipelineEvent<CitedPaperAcquisitionRequestedPayload>, reason: String) {
        pipelineProgressRepository.markBibliographyItem(event.analysisRunId, "evidence", "prepare-evidence", event.payload.bibliographyEntryId, "SKIPPED", reason)
        pipelineProgressRepository.markBibliographyItem(event.analysisRunId, "verification", "assess-and-aggregate", event.payload.bibliographyEntryId, "SKIPPED", reason)
    }

    companion object {
        const val CITED_PAPER_ACCESS_RETRIES_EXHAUSTED = "CITED_PAPER_ACCESS_RETRIES_EXHAUSTED"
    }

}
