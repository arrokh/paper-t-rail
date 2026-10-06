package com.papertrail.api.evidence.queue

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.papertrail.api.analysis.repository.AnalysisRunPipelineProgressRepository
import com.papertrail.api.analysis.repository.AnalysisRunProcessingRepository
import com.papertrail.api.analysis.service.AnalysisRunStageCompletionService
import com.papertrail.api.analysis.execution.service.AnalysisRunExecutionService
import com.papertrail.api.analysis.execution.ExecutionSpanArtifactSpec
import com.papertrail.api.analysis.execution.ExecutionSpanSpec
import com.papertrail.api.analysis.execution.ExecutionOperationId
import com.papertrail.api.document.repository.SourceDocumentRepository
import com.papertrail.api.evidence.service.EvidenceRetrievalService
import com.papertrail.api.infrastructure.messaging.events.PipelineEvent
import com.papertrail.api.infrastructure.messaging.repository.InboxRepository
import com.papertrail.api.evidence.repository.EvidenceRetrievalRepository
import com.papertrail.api.evidence.verification.service.EvidenceVerificationService
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.util.UUID

@Component
class CitedPaperIndexingRequestedHandler(
    private val processingRepository: AnalysisRunProcessingRepository,
    private val sourceDocumentRepository: SourceDocumentRepository,
    private val inboxRepository: InboxRepository,
    private val transactionTemplate: TransactionTemplate,
    private val objectMapper: ObjectMapper,
    private val evidenceRetrievalService: EvidenceRetrievalService,
    private val evidenceRetrievalRepository: EvidenceRetrievalRepository,
    private val evidenceVerificationService: EvidenceVerificationService,
    private val analysisRunStageCompletionService: AnalysisRunStageCompletionService,
    private val pipelineProgressRepository: AnalysisRunPipelineProgressRepository,
    private val executionService: AnalysisRunExecutionService? = null,
) {
    fun isProcessed(eventId: UUID): Boolean = inboxRepository.isProcessed(eventId)

    fun handle(serializedEvent: String): UUID {
        val event: PipelineEvent<CitedPaperIndexingRequestedPayload> = objectMapper.readValue(serializedEvent)
        require(event.eventType == CITED_PAPER_INDEXING_REQUESTED) { "Unsupported event type '${event.eventType}'." }
        executionService?.recordQueueIntervals(
            event.analysisRunId, event.eventId, event.attempt, "evidence", event.queueWaitStartedAt,
            event.retryScheduledAt, event.retryDueAt, event.causationId,
        )
        val operation = { handleEvent(event) }
        return executionService?.record(
            event.analysisRunId,
            ExecutionSpanSpec("evidence", "QUEUE", "Evidence indexing attempt", event.attempt + 1, event.eventId, attributes = mapOf("eventAttempt" to event.attempt), operationId = event.eventId, causationEventId = event.causationId),
            operation,
        ) ?: operation()
    }

    private fun handleEvent(event: PipelineEvent<CitedPaperIndexingRequestedPayload>): UUID {
        if (sourceDocumentRepository.isDeleted(event.payload.documentId)) return event.eventId
        if (isProcessed(event.eventId)) return event.eventId

        val run = processingRepository.queueRun(event.analysisRunId)
            ?: throw IllegalStateException("Queued Cited Paper indexing Analysis Run is missing.")
        if (run.documentId != event.payload.documentId || run.sourceHash != event.payload.sourceContentSha256) {
            throw IllegalStateException("Cited Paper indexing event provenance does not match its Analysis Run.")
        }
        if (run.status != "PROCESSING") throw IllegalStateException("Analysis Run is not accepting Cited Paper indexing work.")

        pipelineProgressRepository.markBibliographyItem(event.analysisRunId, "evidence", "prepare-evidence", event.payload.bibliographyEntryId, "IN_PROGRESS")
        if (executionService == null) {
            evidenceRetrievalService.retrieve(event.analysisRunId, event.payload.bibliographyEntryId)
        } else {
            executionService.record(
                event.analysisRunId,
                ExecutionSpanSpec("evidence", "INTERNAL", "Retrieve and rank evidence passages", event.attempt + 1, event.eventId, operationId = ExecutionOperationId.forEvent(event.eventId, "evidence-retrieval")),
            ) {
                executionService.captureCurrent(
                    ExecutionSpanArtifactSpec("INPUT", "run-stage-input-v1", mapOf("itemCount" to 1)),
                )
                evidenceRetrievalService.retrieve(event.analysisRunId, event.payload.bibliographyEntryId).also {
                    executionService.captureCurrent(
                        ExecutionSpanArtifactSpec("RESULT", "run-stage-result-v1", mapOf("status" to "SUCCEEDED", "itemCount" to 1)),
                    )
                }
            }
        }
        pipelineProgressRepository.markBibliographyItem(event.analysisRunId, "evidence", "prepare-evidence", event.payload.bibliographyEntryId, "COMPLETED")
        pipelineProgressRepository.markBibliographyItem(event.analysisRunId, "verification", "assess-and-aggregate", event.payload.bibliographyEntryId, "IN_PROGRESS")
        if (executionService == null) {
            evidenceVerificationService.verifyReference(event.analysisRunId, event.payload.bibliographyEntryId)
        } else {
            executionService.record(
                event.analysisRunId,
                ExecutionSpanSpec("verification", "PROVIDER", "Assess claim and aggregate result", event.attempt + 1, event.eventId, operationId = ExecutionOperationId.forEvent(event.eventId, "verification")),
            ) {
                executionService.captureCurrent(
                    ExecutionSpanArtifactSpec("INPUT", "run-stage-input-v1", mapOf("itemCount" to 1)),
                )
                evidenceVerificationService.verifyReference(event.analysisRunId, event.payload.bibliographyEntryId)
                val outcome = verificationOutcome(event.analysisRunId, event.payload.bibliographyEntryId)
                val resultStatus = when {
                    outcome.failureReason != null || outcome.pendingFullTextPairs > 0 -> "FAILED"
                    outcome.completedPairs > 0 || outcome.hasJudgements || outcome.hasCompletedSpans -> "SUCCEEDED"
                    else -> "SKIPPED"
                }
                executionService.captureCurrent(
                    ExecutionSpanArtifactSpec(
                        "RESULT",
                        "run-stage-result-v1",
                        mapOf(
                            "status" to resultStatus,
                            "itemCount" to outcome.completedPairs,
                            "failedCount" to if (resultStatus == "FAILED") 1 else 0,
                        ),
                    ),
                )
            }
        }
        val verificationOutcome = verificationOutcome(event.analysisRunId, event.payload.bibliographyEntryId)
        val verificationStatus = when {
            verificationOutcome.failureReason != null -> "FAILED"
            verificationOutcome.aggregationStatus == "PENDING" && verificationOutcome.pendingFullTextPairs > 0 -> "FAILED"
            verificationOutcome.completedPairs > 0 || verificationOutcome.hasJudgements || verificationOutcome.hasCompletedSpans -> "COMPLETED"
            else -> "SKIPPED"
        }
        val verificationReason = when {
            verificationOutcome.failureReason != null -> verificationOutcome.failureReason
            verificationStatus == "FAILED" -> "SYSTEM_ONE_INCOMPLETE"
            verificationStatus == "SKIPPED" -> "NO_EVIDENCE_ASSESSMENT_WORK"
            else -> null
        }
        pipelineProgressRepository.markBibliographyItem(
            event.analysisRunId,
            "verification",
            "assess-and-aggregate",
            event.payload.bibliographyEntryId,
            verificationStatus,
            verificationReason,
        )
        recordProcessed(event)
        return event.eventId
    }

    fun markFailed(event: PipelineEvent<CitedPaperIndexingRequestedPayload>, reason: String) {
        require(event.eventType == CITED_PAPER_INDEXING_REQUESTED) { "Unsupported event type '${event.eventType}'." }
        if (sourceDocumentRepository.isDeleted(event.payload.documentId)) return
        recordProcessed(event, reason)
        executionService?.finishIfTerminal(event.analysisRunId)
    }

    private fun recordProcessed(event: PipelineEvent<CitedPaperIndexingRequestedPayload>, failureReason: String? = null) {
        transactionTemplate.executeWithoutResult {
            val inserted = inboxRepository.insertIfAbsent(
                event.eventId,
                event.analysisRunId,
                CITED_PAPER_INDEXING_HANDLER,
                Instant.now(),
            )
            if (!inserted) return@executeWithoutResult
            if (failureReason != null) {
                val failureCode = when (evidenceRetrievalRepository.status(event.analysisRunId, event.payload.bibliographyEntryId)) {
                    "PENDING" -> {
                        evidenceRetrievalRepository.markFailed(
                            event.analysisRunId,
                            event.payload.bibliographyEntryId,
                            INDEXING_RETRIES_EXHAUSTED,
                        )
                        INDEXING_RETRIES_EXHAUSTED
                    }
                    "COMPLETED" -> EVIDENCE_VERIFICATION_RETRIES_EXHAUSTED
                    else -> EVIDENCE_PROCESSING_INCOMPLETE
                }
                evidenceVerificationService.failFullText(
                    event.analysisRunId,
                    event.payload.bibliographyEntryId,
                    failureCode,
                )
                when (failureCode) {
                    INDEXING_RETRIES_EXHAUSTED -> {
                        pipelineProgressRepository.markBibliographyItem(event.analysisRunId, "evidence", "prepare-evidence", event.payload.bibliographyEntryId, "FAILED", failureCode)
                        pipelineProgressRepository.markBibliographyItem(event.analysisRunId, "verification", "assess-and-aggregate", event.payload.bibliographyEntryId, "SKIPPED", "EVIDENCE_PREPARATION_FAILED")
                    }
                    EVIDENCE_VERIFICATION_RETRIES_EXHAUSTED -> {
                        pipelineProgressRepository.markBibliographyItem(event.analysisRunId, "evidence", "prepare-evidence", event.payload.bibliographyEntryId, "COMPLETED")
                        pipelineProgressRepository.markBibliographyItem(event.analysisRunId, "verification", "assess-and-aggregate", event.payload.bibliographyEntryId, "FAILED", failureCode)
                    }
                    else -> {
                        pipelineProgressRepository.markBibliographyItem(event.analysisRunId, "evidence", "prepare-evidence", event.payload.bibliographyEntryId, "FAILED", failureCode)
                        pipelineProgressRepository.markBibliographyItem(event.analysisRunId, "verification", "assess-and-aggregate", event.payload.bibliographyEntryId, "FAILED", failureCode)
                    }
                }
                processingRepository.updateFailureReasonIfProcessing(event.analysisRunId, failureReason)
            }
            analysisRunStageCompletionService.completeParsedStageIfReady(event.analysisRunId)
        }
    }

    private fun verificationOutcome(analysisRunId: UUID, bibliographyEntryId: UUID): EvidenceRetrievalRepository.VerificationOutcome =
        evidenceRetrievalRepository.verificationOutcome(analysisRunId, bibliographyEntryId)

    companion object {
        const val INDEXING_RETRIES_EXHAUSTED = "INDEXING_RETRIES_EXHAUSTED"
        const val EVIDENCE_VERIFICATION_RETRIES_EXHAUSTED = "EVIDENCE_VERIFICATION_RETRIES_EXHAUSTED"
        const val EVIDENCE_PROCESSING_INCOMPLETE = "EVIDENCE_PROCESSING_INCOMPLETE"
    }
}
