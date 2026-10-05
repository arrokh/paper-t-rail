package com.papertrail.api.analysis.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.papertrail.api.citation.claims.service.ClaimAnalysisService
import com.papertrail.api.citation.claims.service.ClaimCitationPairCounter
import com.papertrail.api.citation.parsing.ParsedDocumentRepository
import com.papertrail.api.citation.parsing.ScientificDocumentParser
import com.papertrail.api.document.storage.SourceDocumentObjectStore
import com.papertrail.api.document.service.isSourceDocumentDeleted
import com.papertrail.api.document.service.requireActiveSourceDocument
import com.papertrail.api.evidence.verification.repository.ClaimReferenceVerificationRepository
import com.papertrail.api.infrastructure.crypto.sha256Hex
import com.papertrail.api.analysis.queue.DOCUMENT_ANALYSIS_HANDLER
import com.papertrail.api.analysis.queue.DOCUMENT_ANALYSIS_REQUESTED
import com.papertrail.api.analysis.queue.DocumentAnalysisRequestedPayload
import com.papertrail.api.analysis.configuration.AnalysisConfigurationSnapshot
import com.papertrail.api.analysis.execution.AnalysisRunExecutionService
import com.papertrail.api.analysis.execution.ExecutionSpanSpec
import com.papertrail.api.analysis.execution.ExecutionSpanArtifactSpec
import com.papertrail.api.analysis.execution.ExecutionOperationId
import com.papertrail.api.infrastructure.messaging.events.PipelineEvent
import com.papertrail.api.infrastructure.messaging.events.W3CTraceContext
import com.papertrail.api.scholarly.references.queue.REFERENCE_RESOLUTION_REQUESTED
import com.papertrail.api.scholarly.references.queue.ReferenceResolutionRequestedPayload
import com.papertrail.api.scholarly.references.service.ReferenceResolutionService
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@Service
class AnalysisRunProcessingService(
    private val jdbc: JdbcTemplate,
    private val transactionTemplate: TransactionTemplate,
    private val objectMapper: ObjectMapper,
    private val objectStore: SourceDocumentObjectStore,
    private val scientificDocumentParser: ScientificDocumentParser,
    private val parsedDocumentRepository: ParsedDocumentRepository,
    private val claimAnalysisService: ClaimAnalysisService,
    private val claimReferenceVerificationRepository: ClaimReferenceVerificationRepository,
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

    fun process(event: PipelineEvent<DocumentAnalysisRequestedPayload>): UUID {
        executionService?.recordQueueIntervals(
            event.analysisRunId, event.eventId, event.attempt, "source", event.queueWaitStartedAt,
            event.retryScheduledAt, event.retryDueAt, event.causationId,
        )
        val operation = {
            processInternal(event)
        }
        return executionService?.record(
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
        ) ?: operation()
    }

    private fun processInternal(event: PipelineEvent<DocumentAnalysisRequestedPayload>): UUID {
        require(event.eventType == DOCUMENT_ANALYSIS_REQUESTED) { "Unsupported event type '${event.eventType}'." }
        if (jdbc.isSourceDocumentDeleted(event.payload.documentId)) return event.eventId
        if (isProcessed(event.eventId)) return event.eventId

        val document = jdbc.query(
            "SELECT object_key, sha256 FROM source_documents WHERE id = ?",
            { rs, _ -> StoredSource(rs.getString("object_key"), rs.getString("sha256")) },
            event.payload.documentId,
        ).firstOrNull() ?: throw IllegalStateException("Queued Source Document is missing.")
        val run = jdbc.query(
            """
            SELECT document_id, source_content_sha256, source_parser_id, source_parser_version,
                   configuration_snapshot::text AS configuration_snapshot
              FROM analysis_runs WHERE id = ?
            """.trimIndent(),
            { rs, _ ->
                RunProvenance(
                    rs.getObject("document_id", UUID::class.java),
                    rs.getString("source_content_sha256"),
                    rs.getString("source_parser_id"),
                    rs.getString("source_parser_version"),
                    objectMapper.readValue(rs.getString("configuration_snapshot"), AnalysisConfigurationSnapshot::class.java),
                )
            },
            event.analysisRunId,
        ).firstOrNull() ?: throw IllegalStateException("Queued Analysis Run is missing.")

        if (run.documentId != event.payload.documentId || run.sourceHash != event.payload.sourceContentSha256 || document.sha256 != run.sourceHash) {
            throw IllegalStateException("Queue event provenance does not match the persisted Source Document and Analysis Run.")
        }
        jdbc.requireActiveSourceDocument(event.payload.documentId)
        val existingParsed = parsedDocumentRepository.find(event.analysisRunId)
        if (existingParsed == null) claimAnalysisService.validateProviderAvailability(run.configuration)
        val content = objectStore.get(document.objectKey)
        if (sha256Hex(content) != run.sourceHash) {
            throw IllegalStateException("Stored Source Document failed its SHA-256 integrity check.")
        }
        val evidenceRetrievalConfigured = evidenceRetrievalConfigured(event.analysisRunId)

        val shouldParse = transactionTemplate.execute {
            val updated = jdbc.update(
                """
                UPDATE analysis_runs
                   SET status = CASE WHEN status = 'QUEUED' THEN 'PROCESSING' ELSE status END,
                       started_at = COALESCE(started_at, now()),
                       progress = jsonb_build_object(
                           'stage', 'PARSING_DOCUMENT',
                           'percent', 10,
                           'message', 'The stored PDF was verified; extracting sections and citations.'
                       ),
                       updated_at = now()
                 WHERE id = ? AND document_id = ? AND source_content_sha256 = ?
                   AND status IN ('QUEUED', 'PROCESSING')
                """.trimIndent(),
                event.analysisRunId,
                event.payload.documentId,
                run.sourceHash,
            )
            updated == 1
        } ?: false
        if (!shouldParse) {
            if (isProcessed(event.eventId)) return event.eventId
            throw IllegalStateException("Analysis Run is not in a parsable state.")
        }
        pipelineProgressRepository.mark(event.analysisRunId, "source", "parse-document", "document", "Source Document", "IN_PROGRESS")

        jdbc.requireActiveSourceDocument(event.payload.documentId)
        if (existingParsed == null) {
            jdbc.requireActiveSourceDocument(event.payload.documentId)
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
            jdbc.requireActiveSourceDocument(event.payload.documentId)
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
                return event.eventId
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
                    val updated = jdbc.update(
                        """
                        UPDATE analysis_runs
                           SET progress = jsonb_build_object(
                               'stage', 'RESOLVING_REFERENCES',
                               'percent', 65,
                               'message', 'Parsed structure and Atomic Claims saved; conservatively resolving supported bibliography entries.'
                           ),
                           updated_at = now()
                         WHERE id = ? AND document_id = ? AND source_content_sha256 = ?
                           AND status = 'PROCESSING'
                        """.trimIndent(),
                        event.analysisRunId,
                        event.payload.documentId,
                        run.sourceHash,
                    )
                    if (updated != 1) throw IllegalStateException("Analysis Run could not advance to reference resolution.")
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

        val progressUpdated = jdbc.update(
            """
            UPDATE analysis_runs
               SET progress = jsonb_build_object(
                   'stage', 'RESOLVING_REFERENCES',
                   'percent', 65,
                   'message', 'Parsed structure and Atomic Claims saved; conservatively resolving supported bibliography entries.'
               ),
               updated_at = now()
             WHERE id = ? AND document_id = ? AND source_content_sha256 = ? AND status = 'PROCESSING'
            """.trimIndent(),
            event.analysisRunId,
            event.payload.documentId,
            run.sourceHash,
        )
        if (progressUpdated != 1) throw IllegalStateException("Analysis Run could not advance to reference resolution.")
        val bibliographyEntries = jdbc.query(
            "SELECT id, local_reference_key FROM bibliography_entries WHERE analysis_run_id = ? ORDER BY entry_order",
            { rs, _ -> AnalysisRunPipelineProgressRepository.BibliographyWorkItem(rs.getObject("id", UUID::class.java), rs.getString("local_reference_key")) },
            event.analysisRunId,
        )
        val resolutionConfigured = referenceResolutionService.isResolutionConfigured(event.analysisRunId)
        val accessConfigured = jdbc.queryForObject(
            "SELECT jsonb_typeof(configuration_snapshot -> 'openAccess') = 'object' FROM analysis_runs WHERE id = ?",
            Boolean::class.java,
            event.analysisRunId,
        ) == true
        val verificationConfigured = jdbc.queryForObject(
            """
            SELECT analysis_run_has_conflict_aware_evidence_coverage(configuration_snapshot)
                OR (configuration_snapshot #>> '{systemOne,provider}' <> 'mock'
                    AND configuration_snapshot #>> '{aggregation,executionStatus}' IN ('NOT_RUN', 'PENDING'))
              FROM analysis_runs
             WHERE id = ?
            """.trimIndent(),
            Boolean::class.java,
            event.analysisRunId,
        ) == true
        transactionTemplate.executeWithoutResult {
            val inserted = jdbc.update(
                "INSERT INTO inbox_events (event_id, analysis_run_id, handler_name, processed_at) VALUES (?, ?, ?, ?) ON CONFLICT (event_id) DO NOTHING",
                event.eventId,
                event.analysisRunId,
                DOCUMENT_ANALYSIS_HANDLER,
                Timestamp.from(Instant.now()),
            )
            if (inserted == 0) return@executeWithoutResult
            pipelineProgressRepository.initializeBibliographyItems(
                event.analysisRunId,
                bibliographyEntries,
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
        return event.eventId
    }

    fun markFailed(event: PipelineEvent<DocumentAnalysisRequestedPayload>, reason: String) {
        if (jdbc.isSourceDocumentDeleted(event.payload.documentId)) return
        transactionTemplate.executeWithoutResult {
            pipelineProgressRepository.mark(event.analysisRunId, "source", "parse-document", "document", "Source Document", "FAILED", "SOURCE_PARSE_FAILED")
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
        executionService?.finishIfTerminal(event.analysisRunId)
    }

    private fun evidenceRetrievalConfigured(analysisRunId: UUID): Boolean = jdbc.queryForObject(
        """
        SELECT COALESCE(
            jsonb_typeof(configuration_snapshot -> 'openAccess') = 'object'
            AND jsonb_typeof(configuration_snapshot -> 'referenceResolution') = 'object'
            AND configuration_snapshot #>> '{referenceResolution,executionStatus}' <> 'NOT_RUN'
            AND jsonb_typeof(configuration_snapshot -> 'embedding') = 'object'
            AND jsonb_typeof(configuration_snapshot -> 'retrieval') = 'object',
            FALSE
        )
          FROM analysis_runs
         WHERE id = ?
        """.trimIndent(),
        Boolean::class.java,
        analysisRunId,
    ) == true

    private fun rejectClaimCitationPairLimit(
        event: PipelineEvent<DocumentAnalysisRequestedPayload>,
        pairCount: Long,
        maximumPairs: Int,
    ) {
        val reason = "CLAIM_CITATION_PAIRS_TOO_MANY: The document produces $pairCount claim-citation pairs; the configured limit is $maximumPairs."
        transactionTemplate.executeWithoutResult {
            pipelineProgressRepository.mark(event.analysisRunId, "source", "parse-document", "document", "Source Document", "FAILED", "CLAIM_CITATION_PAIR_LIMIT_EXCEEDED")
            val failed = jdbc.update(
                """
                UPDATE analysis_runs
                   SET status = 'FAILED',
                       failure_reason = ?,
                       progress = jsonb_build_object('stage', 'FAILED', 'percent', 0, 'message', ?),
                       completed_at = now(),
                       updated_at = now()
                 WHERE id = ? AND document_id = ? AND source_content_sha256 = ?
                   AND status = 'PROCESSING'
                   AND NOT EXISTS (SELECT 1 FROM inbox_events WHERE event_id = ?)
                """.trimIndent(),
                reason,
                reason,
                event.analysisRunId,
                event.payload.documentId,
                event.payload.sourceContentSha256,
                event.eventId,
            )
            if (failed != 1) throw IllegalStateException("Analysis Run could not be rejected for exceeding its claim-citation pair limit.")
            val processed = jdbc.update(
                "INSERT INTO inbox_events (event_id, analysis_run_id, handler_name, processed_at) VALUES (?, ?, ?, ?)",
                event.eventId,
                event.analysisRunId,
                DOCUMENT_ANALYSIS_HANDLER,
                Timestamp.from(Instant.now()),
            )
            if (processed != 1) throw IllegalStateException("Claim-citation pair rejection could not be recorded as processed.")
        }
    }

    private fun insertOutboxEvent(event: PipelineEvent<ReferenceResolutionRequestedPayload>) {
        jdbc.update(
            """
            INSERT INTO outbox_events (
                event_id, event_type, schema_version, analysis_run_id,
                correlation_id, causation_id, occurred_at, payload, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
            """.trimIndent(),
            event.eventId,
            event.eventType,
            event.schemaVersion,
            event.analysisRunId,
            event.correlationId,
            event.causationId,
            Timestamp.from(event.occurredAt),
            objectMapper.writeValueAsString(event),
            Timestamp.from(Instant.now()),
        )
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

    private data class StoredSource(val objectKey: String, val sha256: String)
    private data class RunProvenance(
        val documentId: UUID,
        val sourceHash: String,
        val parserId: String,
        val parserVersion: String,
        val configuration: AnalysisConfigurationSnapshot,
    )

    companion object {
        private val logger = LoggerFactory.getLogger(AnalysisRunProcessingService::class.java)
    }
}
