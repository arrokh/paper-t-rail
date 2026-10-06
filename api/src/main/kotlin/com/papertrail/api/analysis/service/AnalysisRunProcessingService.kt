package com.papertrail.api.analysis.service

import com.papertrail.api.citation.claims.service.ClaimAnalysisService
import com.papertrail.api.citation.claims.service.ClaimCitationPairCounter
import com.papertrail.api.citation.repository.ParsedDocumentRepository
import com.papertrail.api.citation.parsing.ScientificDocumentParser
import com.papertrail.api.document.storage.SourceDocumentObjectStore
import com.papertrail.api.document.repository.SourceDocumentRepository
import com.papertrail.api.evidence.verification.repository.ClaimReferenceVerificationRepository
import com.papertrail.api.infrastructure.crypto.sha256Hex
import com.papertrail.api.analysis.queue.DOCUMENT_ANALYSIS_HANDLER
import com.papertrail.api.analysis.queue.DOCUMENT_ANALYSIS_REQUESTED
import com.papertrail.api.analysis.queue.DocumentAnalysisRequestedPayload
import com.papertrail.api.analysis.execution.service.AnalysisRunExecutionService
import com.papertrail.api.analysis.execution.ExecutionSpanSpec
import com.papertrail.api.analysis.execution.ExecutionSpanArtifactSpec
import com.papertrail.api.analysis.execution.ExecutionOperationId
import com.papertrail.api.analysis.repository.AnalysisRunPipelineProgressRepository
import com.papertrail.api.analysis.repository.AnalysisRunProcessingRepository
import com.papertrail.api.infrastructure.messaging.events.PipelineEvent
import com.papertrail.api.infrastructure.messaging.events.W3CTraceContext
import com.papertrail.api.infrastructure.messaging.repository.InboxRepository
import com.papertrail.api.infrastructure.messaging.repository.OutboxRepository
import com.papertrail.api.scholarly.references.queue.REFERENCE_RESOLUTION_REQUESTED
import com.papertrail.api.scholarly.references.queue.ReferenceResolutionRequestedPayload
import com.papertrail.api.scholarly.references.service.ReferenceResolutionService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.util.UUID

@Service
class AnalysisRunProcessingService(
    private val processingRepository: AnalysisRunProcessingRepository,
    private val sourceDocumentRepository: SourceDocumentRepository,
    private val inboxRepository: InboxRepository,
    private val outboxRepository: OutboxRepository,
    private val transactionTemplate: TransactionTemplate,
    private val objectStore: SourceDocumentObjectStore,
    private val scientificDocumentParser: ScientificDocumentParser,
    private val parsedDocumentRepository: ParsedDocumentRepository,
    private val claimAnalysisService: ClaimAnalysisService,
    private val claimReferenceVerificationRepository: ClaimReferenceVerificationRepository,
    private val referenceResolutionService: ReferenceResolutionService,
    private val analysisRunStageCompletionService: AnalysisRunStageCompletionService,
    private val pipelineProgressRepository: AnalysisRunPipelineProgressRepository,
    private val executionService: AnalysisRunExecutionService? = null,
) {
    private data class ProcessingOutcome(val eventId: UUID, val spanStatus: String)

    fun isProcessed(eventId: UUID): Boolean = inboxRepository.isProcessed(eventId)

    fun process(event: PipelineEvent<DocumentAnalysisRequestedPayload>): UUID {
        executionService?.recordQueueIntervals(
            event.analysisRunId, event.eventId, event.attempt, "source", event.queueWaitStartedAt,
            event.retryScheduledAt, event.retryDueAt, event.causationId,
        )
        val operation = { processInternal(event) }
        val outcome = executionService?.recordWithStatus(
            event.analysisRunId,
            ExecutionSpanSpec(
                stageId = "source",
                kind = "QUEUE",
                name = "Source analysis attempt",
                attempt = event.attempt + 1,
                eventId = event.eventId,
                attributes = mapOf("eventAttempt" to event.attempt),
                operationId = event.eventId,
                causationEventId = event.causationId,
            ),
            operation,
        ) { it.spanStatus } ?: operation()
        return outcome.eventId
    }

    private fun processInternal(event: PipelineEvent<DocumentAnalysisRequestedPayload>): ProcessingOutcome {
        require(event.eventType == DOCUMENT_ANALYSIS_REQUESTED) { "Unsupported event type '${event.eventType}'." }
        if (sourceDocumentRepository.isDeleted(event.payload.documentId)) return ProcessingOutcome(event.eventId, "SKIPPED")
        if (isProcessed(event.eventId)) return ProcessingOutcome(event.eventId, "REUSED")

        val document = processingRepository.sourceObject(event.payload.documentId)
            ?: throw IllegalStateException("Queued Source Document is missing.")
        val run = processingRepository.runProvenance(event.analysisRunId)
            ?: throw IllegalStateException("Queued Analysis Run is missing.")

        if (run.documentId != event.payload.documentId || run.sourceHash != event.payload.sourceContentSha256 || document.sha256 != run.sourceHash) {
            throw IllegalStateException("Queue event provenance does not match the persisted Source Document and Analysis Run.")
        }
        sourceDocumentRepository.requireActiveSourceDocument(event.payload.documentId)
        val existingParsed = parsedDocumentRepository.find(event.analysisRunId)
        if (existingParsed == null) claimAnalysisService.validateProviderAvailability(run.configuration)
        val content = objectStore.get(document.objectKey)
        if (sha256Hex(content) != run.sourceHash) {
            throw IllegalStateException("Stored Source Document failed its SHA-256 integrity check.")
        }
        val evidenceRetrievalConfigured = evidenceRetrievalConfigured(event.analysisRunId)

        val shouldParse = transactionTemplate.execute {
            processingRepository.markProcessing(event.analysisRunId, event.payload.documentId, run.sourceHash)
        } ?: false
        if (!shouldParse) {
            if (isProcessed(event.eventId)) return ProcessingOutcome(event.eventId, "REUSED")
            throw IllegalStateException("Analysis Run is not in a parsable state.")
        }
        pipelineProgressRepository.mark(event.analysisRunId, "source", "parse-document", "document", "Source Document", "IN_PROGRESS")

        sourceDocumentRepository.requireActiveSourceDocument(event.payload.documentId)
        if (existingParsed == null) {
            sourceDocumentRepository.requireActiveSourceDocument(event.payload.documentId)
            val parsed = if (executionService == null) {
                scientificDocumentParser.parse(content)
            } else {
                val parserSpan = executionService.startSpan(
                    event.analysisRunId,
                    ExecutionSpanSpec(
                        stageId = "source",
                        kind = "PROVIDER",
                        name = "Parse Source Document",
                        attempt = event.attempt + 1,
                        eventId = event.eventId,
                        providerId = run.parserId,
                        modelId = run.parserVersion,
                        operationId = ExecutionOperationId.forEvent(event.eventId, "source-parser"),
                    ),
                )
                if (parserSpan != null) {
                    executionService.capture(
                        event.analysisRunId,
                        parserSpan.id,
                        ExecutionSpanArtifactSpec(
                            role = "INPUT",
                            schemaVersion = "source-parser-input-v1",
                            fields = mapOf(
                                "parserId" to run.parserId,
                                "parserVersion" to run.parserVersion,
                                "sourceSha256" to run.sourceHash,
                                "inputBytes" to content.size,
                            ),
                        ),
                    )
                    executionService.capture(
                        event.analysisRunId,
                        parserSpan.id,
                        ExecutionSpanArtifactSpec("REQUEST", "source-document-pdf-request-v1", emptyMap()),
                    )
                }
                try {
                    scientificDocumentParser.parse(content).also { output ->
                        if (parserSpan != null) {
                            executionService.capture(
                                event.analysisRunId,
                                parserSpan.id,
                                ExecutionSpanArtifactSpec("RESPONSE", "source-parser-response-v1", emptyMap()),
                            )
                            executionService.capture(
                                event.analysisRunId,
                                parserSpan.id,
                                ExecutionSpanArtifactSpec(
                                    role = "RESULT",
                                    schemaVersion = "run-stage-result-v1",
                                    fields = mapOf(
                                        "status" to "SUCCEEDED",
                                        "itemCount" to output.sections.size,
                                        "completedCount" to output.bibliographyEntries.size,
                                        "failedCount" to 0,
                                    ),
                                ),
                            )
                        }
                        executionService.finishSpan(parserSpan, "SUCCEEDED")
                    }
                } catch (exception: Exception) {
                    if (parserSpan != null) {
                        executionService.capture(
                            event.analysisRunId,
                            parserSpan.id,
                            ExecutionSpanArtifactSpec("RESPONSE", "source-parser-response-v1", emptyMap()),
                        )
                    }
                    executionService.finishSpan(parserSpan, "FAILED", "PARSER_FAILURE")
                    throw exception
                }
            }
            if (parsed.parserId != run.parserId || parsed.parserVersion != run.parserVersion) {
                throw IllegalStateException("The scientific parser identity does not match the Analysis Run provenance.")
            }
            require(parsed.rawParserOutput.isNotEmpty()) { "The scientific parser returned no raw parser output." }
            sourceDocumentRepository.requireActiveSourceDocument(event.payload.documentId)
            val analyzedClaims = executionService?.record(
                event.analysisRunId,
                ExecutionSpanSpec(
                    stageId = "source",
                    kind = "PROVIDER",
                    name = "Analyze Atomic Claims",
                    attempt = event.attempt + 1,
                    eventId = event.eventId,
                    providerId = run.configuration.claimExtractor.provider,
                    modelId = run.configuration.claimExtractor.model,
                    operationId = ExecutionOperationId.forEvent(event.eventId, "claim-analysis"),
                ),
            ) {
                executionService.captureCurrent(
                    ExecutionSpanArtifactSpec(
                        "INPUT",
                        "run-stage-input-v1",
                        mapOf("itemCount" to parsed.citationContexts.size, "candidateCount" to parsed.bibliographyEntries.size),
                    ),
                )
                claimAnalysisService.analyze(parsed, run.configuration).also { analyzed ->
                    executionService.captureCurrent(
                        ExecutionSpanArtifactSpec(
                            "RESULT",
                            "run-stage-result-v1",
                            mapOf("status" to "SUCCEEDED", "itemCount" to analyzed.sumOf { it.claims.size }),
                        ),
                    )
                }
            }
                ?: claimAnalysisService.analyze(parsed, run.configuration)
            val claimCitationPairCount = ClaimCitationPairCounter.count(parsed, analyzedClaims)
            val maxClaimCitationPairs = run.configuration.validationLimits.maxClaimCitationPairs
            if (claimCitationPairCount > maxClaimCitationPairs.toLong()) {
                rejectClaimCitationPairLimit(event, claimCitationPairCount, maxClaimCitationPairs)
                return ProcessingOutcome(event.eventId, "SUCCEEDED")
            }
            val rawTeiObjectKey =
                "source/${event.payload.documentId}/analysis-runs/${event.analysisRunId}/grobid-${sha256Hex(parsed.rawParserOutput)}.xml"
            objectStore.put(rawTeiObjectKey, parsed.rawParserOutput, "application/xml")
            try {
                transactionTemplate.executeWithoutResult {
                    parsedDocumentRepository.save(event.analysisRunId, run.sourceHash, parsed, rawTeiObjectKey, analyzedClaims)
                    pipelineProgressRepository.mark(event.analysisRunId, "source", "parse-document", "document", "Source Document", "COMPLETED")
                    if (evidenceRetrievalConfigured) {
                        claimReferenceVerificationRepository.initializeExpectedPairs(event.analysisRunId)
                    }
                    val updated = processingRepository.advanceToReferenceResolution(
                        event.analysisRunId,
                        event.payload.documentId,
                        run.sourceHash,
                    )
                    if (!updated) throw IllegalStateException("Analysis Run could not advance to reference resolution.")
                }
            } catch (exception: Exception) {
                cleanupRawTeiIfUnreferenced(event.analysisRunId, rawTeiObjectKey)
                throw exception
            }
        } else if (existingParsed.sourceContentSha256 != run.sourceHash ||
            existingParsed.parser.provider != run.parserId || existingParsed.parser.version != run.parserVersion
        ) {
            throw IllegalStateException("Persisted parse provenance does not match the Analysis Run.")
        } else {
            pipelineProgressRepository.mark(event.analysisRunId, "source", "parse-document", "document", "Source Document", "COMPLETED")
        }

        val progressUpdated = processingRepository.advanceToReferenceResolution(
            event.analysisRunId,
            event.payload.documentId,
            run.sourceHash,
        )
        if (!progressUpdated) throw IllegalStateException("Analysis Run could not advance to reference resolution.")
        val bibliographyEntries = processingRepository.bibliographyWorkItems(event.analysisRunId)
        val progressBibliographyEntries = bibliographyEntries.map {
            AnalysisRunPipelineProgressRepository.BibliographyWorkItem(it.id, it.referenceKey)
        }
        val resolutionConfigured = referenceResolutionService.isResolutionConfigured(event.analysisRunId)
        val accessConfigured = processingRepository.accessConfigured(event.analysisRunId)
        val verificationConfigured = processingRepository.verificationConfigured(event.analysisRunId)
        transactionTemplate.executeWithoutResult {
            val inserted = inboxRepository.insertIfAbsent(
                event.eventId,
                event.analysisRunId,
                DOCUMENT_ANALYSIS_HANDLER,
                Instant.now(),
            )
            if (!inserted) return@executeWithoutResult
            pipelineProgressRepository.initializeBibliographyItems(
                event.analysisRunId,
                progressBibliographyEntries,
                resolutionConfigured,
                accessConfigured,
                evidenceRetrievalConfigured,
                verificationConfigured,
            )
            if (resolutionConfigured) {
                bibliographyEntries.forEach { entry ->
                    val resolutionEvent = PipelineEvent(
                        eventId = UUID.randomUUID(),
                        eventType = REFERENCE_RESOLUTION_REQUESTED,
                        schemaVersion = 1,
                        analysisRunId = event.analysisRunId,
                        correlationId = event.correlationId,
                        causationId = event.eventId,
                        occurredAt = Instant.now(),
                        attempt = 0,
                        payload = ReferenceResolutionRequestedPayload(
                            documentId = event.payload.documentId,
                            sourceContentSha256 = run.sourceHash,
                            bibliographyEntryId = entry.id,
                        ),
                        traceparent = W3CTraceContext.child(event.traceparent),
                        tracestate = event.tracestate,
                    )
                    insertOutboxEvent(resolutionEvent)
                }
            }
            analysisRunStageCompletionService.completeParsedStageIfReady(event.analysisRunId)
        }
        return ProcessingOutcome(event.eventId, "SUCCEEDED")
    }

    fun markFailed(event: PipelineEvent<DocumentAnalysisRequestedPayload>, reason: String) {
        if (sourceDocumentRepository.isDeleted(event.payload.documentId)) return
        transactionTemplate.executeWithoutResult {
            pipelineProgressRepository.mark(event.analysisRunId, "source", "parse-document", "document", "Source Document", "FAILED", "SOURCE_PARSE_FAILED")
            processingRepository.failQueuedRun(
                event.analysisRunId,
                event.payload.documentId,
                event.payload.sourceContentSha256,
                event.eventId,
                reason,
            )
        }
        executionService?.finishIfTerminal(event.analysisRunId)
    }

    private fun evidenceRetrievalConfigured(analysisRunId: UUID): Boolean =
        processingRepository.evidenceRetrievalConfigured(analysisRunId)

    private fun rejectClaimCitationPairLimit(
        event: PipelineEvent<DocumentAnalysisRequestedPayload>,
        pairCount: Long,
        maximumPairs: Int,
    ) {
        val reason = "CLAIM_CITATION_PAIRS_TOO_MANY: The document produces $pairCount claim-citation pairs; the configured limit is $maximumPairs."
        transactionTemplate.executeWithoutResult {
            pipelineProgressRepository.mark(event.analysisRunId, "source", "parse-document", "document", "Source Document", "FAILED", "CLAIM_CITATION_PAIR_LIMIT_EXCEEDED")
            val failed = processingRepository.failProcessingRun(
                event.analysisRunId,
                event.payload.documentId,
                event.payload.sourceContentSha256,
                event.eventId,
                reason,
            )
            if (failed != 1) throw IllegalStateException("Analysis Run could not be rejected for exceeding its claim-citation pair limit.")
            val processed = inboxRepository.insert(
                event.eventId,
                event.analysisRunId,
                DOCUMENT_ANALYSIS_HANDLER,
                Instant.now(),
            )
            if (!processed) throw IllegalStateException("Claim-citation pair rejection could not be recorded as processed.")
        }
    }

    private fun insertOutboxEvent(event: PipelineEvent<ReferenceResolutionRequestedPayload>) {
        outboxRepository.insert(event)
    }

    private fun cleanupRawTeiIfUnreferenced(analysisRunId: UUID, objectKey: String) {
        val referencedKey = try {
            parsedDocumentRepository.rawTeiObjectKey(analysisRunId)
        } catch (exception: Exception) {
            logger.atWarn()
                .addKeyValue("analysisRunId", analysisRunId)
                .addKeyValue("errorType", exception.javaClass.simpleName)
                .log("Could not verify raw GROBID object references; preserving parser output")
            return
        }
        if (referencedKey == objectKey) return
        runCatching { objectStore.delete(objectKey) }
            .onFailure { exception ->
                logger.atWarn()
                    .addKeyValue("analysisRunId", analysisRunId)
                    .addKeyValue("errorType", exception.javaClass.simpleName)
                    .log("Failed to clean up an unreferenced raw GROBID object")
            }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(AnalysisRunProcessingService::class.java)
    }
}
