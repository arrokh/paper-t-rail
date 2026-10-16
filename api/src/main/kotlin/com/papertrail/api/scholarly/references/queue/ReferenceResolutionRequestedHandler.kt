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
import com.papertrail.api.infrastructure.messaging.PipelineHandlerDisposition
import com.papertrail.api.infrastructure.messaging.PipelineHandlerFailureDisposition
import com.papertrail.api.infrastructure.messaging.events.W3CTraceContext
import com.papertrail.api.infrastructure.messaging.repository.InboxRepository
import com.papertrail.api.infrastructure.messaging.repository.OutboxRepository
import com.papertrail.api.scholarly.references.events.REFERENCE_RESOLUTION_HANDLER
import com.papertrail.api.scholarly.references.events.REFERENCE_RESOLUTION_REQUESTED
import com.papertrail.api.scholarly.references.events.ReferenceResolutionRequestedPayload
import com.papertrail.api.scholarly.references.repository.ReferenceResolutionRepository
import com.papertrail.api.scholarly.references.attempts.domain.ReferenceResolutionChainClaimResult
import com.papertrail.api.scholarly.references.attempts.domain.ReferenceResolutionQueueFailureArbitration
import com.papertrail.api.scholarly.references.service.ReferenceResolutionService
import com.papertrail.api.scholarly.references.service.ProviderChainResolutionDisposition
import com.papertrail.api.analysis.successor.service.SuccessorSelectedAssetService
import com.papertrail.api.analysis.recovery.domain.RecoveryWorkEventType
import com.papertrail.api.analysis.recovery.service.RecoveryHistoryService
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
    private val successorSelectedAssetService: SuccessorSelectedAssetService,
    private val executionService: AnalysisRunExecutionService? = null,
    private val recoveryHistoryService: RecoveryHistoryService? = null,
) : ReferenceResolutionQueueHandler {
    override fun isProcessed(eventId: UUID): Boolean = inboxRepository.isProcessed(eventId)

    fun handle(serializedEvent: String): UUID {
        val event: PipelineEvent<ReferenceResolutionRequestedPayload> = JsonUtil.fromJson(serializedEvent)
        check(handleWithDisposition(serializedEvent) == PipelineHandlerDisposition.Done) {
            "A queue disposition must be consumed by the Redis Stream worker."
        }
        return event.eventId
    }

    override fun handleWithDisposition(serializedEvent: String): PipelineHandlerDisposition {
        val event: PipelineEvent<ReferenceResolutionRequestedPayload> = JsonUtil.fromJson(serializedEvent)
        require(event.eventType == REFERENCE_RESOLUTION_REQUESTED) { "Unsupported event type '${event.eventType}'." }
        if (sourceDocumentRepository.isDeleted(event.payload.documentId) || isProcessed(event.eventId)) {
            return PipelineHandlerDisposition.Done
        }
        val run = processingRepository.queueRun(event.analysisRunId)
            ?: throw IllegalStateException("Queued Analysis Run is missing.")
        validateRunProvenance(event, run)
        val chainClaim = when (val claim = referenceResolutionService.claimProviderChainEntry(
            event.analysisRunId,
            event.payload.bibliographyEntryId,
        )) {
            is ReferenceResolutionChainClaimResult.Deferred -> return PipelineHandlerDisposition.Defer(claim.until)
            ReferenceResolutionChainClaimResult.MissingEntry ->
                throw IllegalStateException("Reference-resolution event bibliography entry is missing.")
            else -> claim
        }

        executionService?.recordQueueIntervals(
            event.analysisRunId, event.eventId, event.attempt, "references", event.queueWaitStartedAt,
            event.retryScheduledAt, event.retryDueAt, event.causationId,
        )
        recoveryHistoryService?.recordSuccessorWorkEvent(
            successorAnalysisRunId = event.analysisRunId,
            bibliographyEntryId = event.payload.bibliographyEntryId,
            eventType = RecoveryWorkEventType.ATTEMPT_STARTED,
            outcomeCode = "REFERENCE_RESOLUTION_ATTEMPT_STARTED",
            stageCode = "reference-resolution",
            attemptNumber = event.attempt + 1,
            sourceEventId = event.eventId,
        )
        var activeStage = "reference-resolution"
        val operation = {
            try {
                handleEvent(event, run, chainClaim) { stage -> activeStage = stage }
            } catch (exception: Exception) {
                recoveryHistoryService?.recordSuccessorWorkEvent(
                    successorAnalysisRunId = event.analysisRunId,
                    bibliographyEntryId = event.payload.bibliographyEntryId,
                    eventType = RecoveryWorkEventType.ATTEMPT_FAILED,
                    outcomeCode = if (activeStage == "selected-access") "SELECTED_ACCESS_HANDOFF_FAILED" else "REFERENCE_RESOLUTION_ATTEMPT_FAILED",
                    stageCode = activeStage,
                    attemptNumber = event.attempt + 1,
                    sourceEventId = event.eventId,
                )
                throw exception
            }
        }
        return if (executionService == null) {
            operation()
        } else {
            executionService.recordWithStatus(
                event.analysisRunId,
                ExecutionSpanSpec("references", "QUEUE", "Reference resolution attempt", event.attempt + 1, event.eventId, attributes = mapOf("eventAttempt" to event.attempt), operationId = event.eventId, causationEventId = event.causationId),
                operation,
            ) { disposition -> if (disposition == PipelineHandlerDisposition.Done) "SUCCEEDED" else "DEFERRED" }
        }
    }

    private fun handleEvent(
        event: PipelineEvent<ReferenceResolutionRequestedPayload>,
        run: AnalysisRunProcessingRepository.QueueRun,
        chainClaim: ReferenceResolutionChainClaimResult?,
        setActiveStage: (String) -> Unit,
    ): PipelineHandlerDisposition {
        if (sourceDocumentRepository.isDeleted(event.payload.documentId) || isProcessed(event.eventId)) {
            return PipelineHandlerDisposition.Done
        }
        validateRunProvenance(event, run)
        if (chainClaim is ReferenceResolutionChainClaimResult.AlreadyFinalized) {
            if (!referenceResolutionService.isEntryResolved(event.analysisRunId, event.payload.bibliographyEntryId)) {
                throw IllegalStateException("Terminal provider-chain state has no persisted bibliography resolution.")
            }
            recordProcessed(event, setActiveStage = setActiveStage)
            return PipelineHandlerDisposition.Done
        }

        pipelineProgressRepository.markBibliographyItem(event.analysisRunId, "references", "resolve-entry", event.payload.bibliographyEntryId, "IN_PROGRESS")
        val claimedChain = chainClaim as? ReferenceResolutionChainClaimResult.Claimed
        val resolve = {
            referenceResolutionService.resolveEntry(
                analysisRunId = event.analysisRunId,
                bibliographyEntryId = event.payload.bibliographyEntryId,
                chainClaim = claimedChain,
                onChainFinalized = claimedChain?.let { { recordProcessed(event, setActiveStage = setActiveStage) } },
            )
        }
        val resolutionDisposition = if (executionService == null) {
            resolve()
        } else {
            executionService.record(
                event.analysisRunId,
                ExecutionSpanSpec("references", "INTERNAL", "Resolve bibliography entry", event.attempt + 1, event.eventId, operationId = ExecutionOperationId.forEvent(event.eventId, "reference-resolution")),
            ) {
                executionService.captureCurrent(
                    ExecutionSpanArtifactSpec("INPUT", "run-stage-input-v1", mapOf("itemCount" to 1)),
                )
                resolve().also { result ->
                    executionService.captureCurrent(
                        ExecutionSpanArtifactSpec(
                            "RESULT",
                            "run-stage-result-v1",
                            mapOf("status" to if (result is ProviderChainResolutionDisposition.Deferred) "DEFERRED" else "SUCCEEDED", "itemCount" to 1),
                        ),
                    )
                }
            }
        }
        if (resolutionDisposition is ProviderChainResolutionDisposition.Deferred) {
            return PipelineHandlerDisposition.Defer(resolutionDisposition.until)
        }
        if (resolutionDisposition == null) recordProcessed(event, setActiveStage = setActiveStage)
        return PipelineHandlerDisposition.Done
    }

    private fun validateRunProvenance(
        event: PipelineEvent<ReferenceResolutionRequestedPayload>,
        run: AnalysisRunProcessingRepository.QueueRun,
    ) {
        if (run.documentId != event.payload.documentId || run.sourceHash != event.payload.sourceContentSha256) {
            throw IllegalStateException("Reference-resolution event provenance does not match its Analysis Run.")
        }
        if (run.status != "PROCESSING") {
            throw IllegalStateException("Analysis Run is not accepting reference-resolution work.")
        }
    }

    override fun markFailed(
        event: PipelineEvent<ReferenceResolutionRequestedPayload>,
        reason: String,
    ): PipelineHandlerFailureDisposition {
        require(event.eventType == REFERENCE_RESOLUTION_REQUESTED) { "Unsupported event type '${event.eventType}'." }
        if (sourceDocumentRepository.isDeleted(event.payload.documentId) || isProcessed(event.eventId)) {
            return PipelineHandlerFailureDisposition.AlreadyCompleted
        }
        val arbitration = referenceResolutionService.arbitrateQueueFailure(
            analysisRunId = event.analysisRunId,
            bibliographyEntryId = event.payload.bibliographyEntryId,
            persistFailure = { recordProcessed(event, reason) },
            persistResolved = { recordProcessed(event) },
        )
        return when (arbitration) {
            ReferenceResolutionQueueFailureArbitration.FailureRecorded -> {
                executionService?.finishIfTerminal(event.analysisRunId)
                PipelineHandlerFailureDisposition.FailureRecorded
            }
            ReferenceResolutionQueueFailureArbitration.ResolutionAlreadySaved -> {
                executionService?.finishIfTerminal(event.analysisRunId)
                PipelineHandlerFailureDisposition.AlreadyCompleted
            }
            is ReferenceResolutionQueueFailureArbitration.Defer ->
                PipelineHandlerFailureDisposition.Defer(arbitration.until)
        }
    }

    private fun recordProcessed(
        event: PipelineEvent<ReferenceResolutionRequestedPayload>,
        failureReason: String? = null,
        setActiveStage: (String) -> Unit = {},
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
                recoveryHistoryService?.recordSuccessorWorkEvent(
                    successorAnalysisRunId = event.analysisRunId,
                    bibliographyEntryId = event.payload.bibliographyEntryId,
                    eventType = RecoveryWorkEventType.ATTEMPT_FAILED,
                    outcomeCode = "REFERENCE_RESOLUTION_RETRIES_EXHAUSTED",
                    stageCode = "reference-resolution",
                    attemptNumber = event.attempt + 1,
                    sourceEventId = event.eventId,
                )
                referenceResolutionService.failResolution(
                    event.analysisRunId,
                    event.payload.bibliographyEntryId,
                    REFERENCE_RESOLUTION_RETRIES_EXHAUSTED,
                )
                successorSelectedAssetService.scheduleCleanupIfUnowned(
                    event.analysisRunId,
                    event.payload.bibliographyEntryId,
                )
                processingRepository.updateFailureReasonIfProcessing(event.analysisRunId, failureReason)
                pipelineProgressRepository.markBibliographyItem(
                    event.analysisRunId, "references", "resolve-entry", event.payload.bibliographyEntryId,
                    "FAILED", REFERENCE_RESOLUTION_RETRIES_EXHAUSTED,
                )
                skipDownstream(event, REFERENCE_RESOLUTION_RETRIES_EXHAUSTED)
            } else {
                recoveryHistoryService?.recordSuccessorWorkEvent(
                    successorAnalysisRunId = event.analysisRunId,
                    bibliographyEntryId = event.payload.bibliographyEntryId,
                    eventType = RecoveryWorkEventType.ATTEMPT_COMPLETED,
                    outcomeCode = "REFERENCE_RESOLUTION_COMPLETED",
                    stageCode = "reference-resolution",
                    attemptNumber = event.attempt + 1,
                    sourceEventId = event.eventId,
                )
                pipelineProgressRepository.markBibliographyItem(event.analysisRunId, "references", "resolve-entry", event.payload.bibliographyEntryId, "COMPLETED")
                if (successorSelectedAssetService.isIdentityOnly(event.analysisRunId, event.payload.bibliographyEntryId)) {
                    skipDownstream(event, "DOI_ONLY_NO_PDF_ASSET")
                } else {
                    setActiveStage("selected-access")
                    recoveryHistoryService?.recordSuccessorWorkEvent(
                        successorAnalysisRunId = event.analysisRunId,
                        bibliographyEntryId = event.payload.bibliographyEntryId,
                        eventType = RecoveryWorkEventType.ATTEMPT_STARTED,
                        outcomeCode = "SELECTED_ACCESS_HANDOFF_STARTED",
                        stageCode = "selected-access",
                        attemptNumber = event.attempt + 1,
                        sourceEventId = event.eventId,
                    )
                    val handoff = successorSelectedAssetService.handoffIfSelected(
                        analysisRunId = event.analysisRunId,
                        bibliographyEntryId = event.payload.bibliographyEntryId,
                        identityResolved = referenceResolutionRepository.isResolved(event.analysisRunId, event.payload.bibliographyEntryId),
                        documentId = event.payload.documentId,
                        sourceContentSha256 = event.payload.sourceContentSha256,
                        correlationId = event.correlationId,
                        causationId = event.eventId,
                        traceparent = W3CTraceContext.child(event.traceparent),
                        tracestate = event.tracestate,
                    )
                    when (handoff) {
                        SuccessorSelectedAssetService.HandoffResult.NOT_SELECTED -> {
                            if (!enqueueAcquisitionIfResolved(event)) skipDownstream(event, accessSkipReason(event))
                        }
                        SuccessorSelectedAssetService.HandoffResult.IDENTITY_UNRESOLVED -> {
                            recoveryHistoryService?.recordSuccessorWorkEvent(
                                successorAnalysisRunId = event.analysisRunId,
                                bibliographyEntryId = event.payload.bibliographyEntryId,
                                eventType = RecoveryWorkEventType.ATTEMPT_FAILED,
                                outcomeCode = "ACCESS_SKIPPED_IDENTITY_UNRESOLVED",
                                stageCode = "selected-access",
                                attemptNumber = event.attempt + 1,
                                sourceEventId = event.eventId,
                            )
                            skipDownstream(event, "ACCESS_SKIPPED_IDENTITY_UNRESOLVED")
                        }
                        SuccessorSelectedAssetService.HandoffResult.QUEUED ->
                            recoveryHistoryService?.recordSuccessorWorkEvent(
                                successorAnalysisRunId = event.analysisRunId,
                                bibliographyEntryId = event.payload.bibliographyEntryId,
                                eventType = RecoveryWorkEventType.ATTEMPT_COMPLETED,
                                outcomeCode = "SELECTED_ACCESS_HANDOFF_COMPLETED",
                                stageCode = "selected-access",
                                attemptNumber = event.attempt + 1,
                                sourceEventId = event.eventId,
                            )
                    }
                }
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
        listOf("selected-access", "evidence-retrieval", "claim-verification").forEach { stage ->
            recoveryHistoryService?.recordSuccessorWorkEvent(
                successorAnalysisRunId = event.analysisRunId,
                bibliographyEntryId = event.payload.bibliographyEntryId,
                eventType = RecoveryWorkEventType.WORK_SKIPPED,
                outcomeCode = reason,
                stageCode = stage,
                attemptNumber = null,
                sourceEventId = event.eventId,
            )
        }
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
