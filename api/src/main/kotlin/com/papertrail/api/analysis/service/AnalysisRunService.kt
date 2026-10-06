package com.papertrail.api.analysis.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.papertrail.api.analysis.configuration.AnalysisConfigurationSnapshot
import com.papertrail.api.analysis.configuration.RunConfigurationFactory
import com.papertrail.api.analysis.http.AnalysisRunPage
import com.papertrail.api.analysis.http.AnalysisRunSummary
import com.papertrail.api.analysis.http.AnalysisRunSourcePdfAccess
import com.papertrail.api.analysis.http.CreatedAnalysisRunResponse
import com.papertrail.api.analysis.http.RunConfigurationRequest
import com.papertrail.api.analysis.pagination.AnalysisRunCursorCodec
import com.papertrail.api.analysis.pagination.Direction
import com.papertrail.api.analysis.repository.AnalysisRunRepository
import com.papertrail.api.analysis.repository.AnalysisRunPipelineProgressRepository
import com.papertrail.api.analysis.queue.DOCUMENT_ANALYSIS_REQUESTED
import com.papertrail.api.analysis.queue.DocumentAnalysisRequestedPayload
import com.papertrail.api.citation.repository.ParsedDocumentRepository
import com.papertrail.api.citation.repository.ParsedDocumentView
import com.papertrail.api.document.domain.SourceDocumentDeletedException
import com.papertrail.api.document.repository.SourceDocumentRepository
import com.papertrail.api.document.validation.PdfDocumentValidator
import com.papertrail.api.infrastructure.storage.SourceDocumentObjectStore
import com.papertrail.api.infrastructure.storage.SourceObjectMetadata
import com.papertrail.api.infrastructure.crypto.sha256Hex
import com.papertrail.api.infrastructure.messaging.events.PipelineEvent
import com.papertrail.api.infrastructure.messaging.events.W3CTraceContext
import com.papertrail.api.infrastructure.messaging.repository.OutboxRepository
import com.papertrail.api.analysis.execution.repository.AnalysisRunExecutionRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.server.ResponseStatusException
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID

@Service
class AnalysisRunService(
    private val analysisRunRepository: AnalysisRunRepository,
    private val sourceDocumentRepository: SourceDocumentRepository,
    private val analysisRunExecutionRepository: AnalysisRunExecutionRepository,
    private val outboxRepository: OutboxRepository,
    private val transactionTemplate: TransactionTemplate,
    private val validator: PdfDocumentValidator,
    private val objectStore: SourceDocumentObjectStore,
    private val configurationFactory: RunConfigurationFactory,
    private val parsedDocumentRepository: ParsedDocumentRepository,
    private val objectMapper: ObjectMapper,
    @Value("\${paper-trail.analysis.parser-id}") private val parserId: String,
    @Value("\${paper-trail.analysis.parser-version}") private val parserVersion: String,
    private val pipelineProgressRepository: AnalysisRunPipelineProgressRepository,
) {
    fun maxUploadBytes(): Long = validator.limits.maxBytes

    fun parseConfigurationRequest(configuration: String?): RunConfigurationRequest {
        if (configuration.isNullOrBlank()) return configurationFactory.parseRequest(null)
        val node = try {
            objectMapper.readTree(configuration)
        } catch (exception: Exception) {
            throw IllegalArgumentException("Analysis configuration must be valid JSON.")
        }
        return configurationFactory.parseRequest(node)
    }

    fun createFromUpload(
        filename: String?,
        contentType: String?,
        bytes: ByteArray,
        requestedConfiguration: RunConfigurationRequest,
    ): CreatedAnalysisRunResponse {
        val hash = sha256Hex(bytes)
        val validPdf = validator.validate(filename, contentType, bytes, hash)
        val configuration = configurationFactory.from(requestedConfiguration)
        val documentId = UUID.randomUUID()
        val runId = UUID.randomUUID()
        val eventId = UUID.randomUUID()
        val objectKey = "source/$documentId/$hash.pdf"
        val createdAt = Instant.now()

        try {
            objectStore.put(objectKey, bytes)
        } catch (exception: Exception) {
            logger.atWarn()
                .addKeyValue("documentId", documentId)
                .addKeyValue("analysisRunId", runId)
                .addKeyValue("errorType", exception.javaClass.simpleName)
                .log("Source Document storage unavailable")
            throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Source Document storage is temporarily unavailable.")
        }
        var transactionBodyCompleted = false
        try {
            transactionTemplate.executeWithoutResult {
                sourceDocumentRepository.insert(
                    documentId = documentId,
                    objectKey = objectKey,
                    pdf = validPdf,
                    parserId = validator.parserId,
                    parserVersion = validator.parserVersion,
                    createdAt = createdAt,
                )
                insertRunAndOutbox(
                    documentId = documentId,
                    runId = runId,
                    eventId = eventId,
                    sourceHash = validPdf.sha256,
                    configuration = configuration,
                    createdAt = createdAt,
                )
                transactionBodyCompleted = true
            }
        } catch (exception: Exception) {
            if (transactionBodyCompleted) {
                // A commit failure can be ambiguous: deleting the object may break a committed run.
                logger.atWarn()
                    .addKeyValue("documentId", documentId)
                    .addKeyValue("analysisRunId", runId)
                    .log("Database commit outcome is uncertain; keeping the uploaded source object")
            } else {
                runCatching { objectStore.delete(objectKey) }
                    .onFailure { cleanupError ->
                        logger.atError()
                            .addKeyValue("documentId", documentId)
                            .addKeyValue("analysisRunId", runId)
                            .addKeyValue("errorType", cleanupError.javaClass.simpleName)
                            .log("Failed to clean up an unreferenced source object after database failure")
                    }
            }
            throw exception
        }

        return CreatedAnalysisRunResponse(
            documentId = documentId,
            analysisRunId = runId,
            filename = validPdf.sanitizedFilename,
            sourceContentSha256 = validPdf.sha256,
            status = "QUEUED",
            createdAt = createdAt,
        )
    }

    fun createReanalysis(documentId: UUID, configurationNode: JsonNode?): CreatedAnalysisRunResponse {
        val document = sourceDocumentRepository.findActive(documentId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Source Document not found.")
        val content = try {
            objectStore.get(document.objectKey)
        } catch (exception: Exception) {
            throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "The stored Source Document is temporarily unavailable.")
        }
        val actualHash = sha256Hex(content)
        if (actualHash != document.sha256) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "The stored Source Document hash does not match its recorded SHA-256.")
        }
        val validPdf = validator.validate(document.filename, "application/pdf", content, actualHash)
        val configuration = configurationFactory.from(configurationFactory.parseRequest(configurationNode))
        val runId = UUID.randomUUID()
        val eventId = UUID.randomUUID()
        val createdAt = Instant.now()

        transactionTemplate.executeWithoutResult {
            insertRunAndOutbox(
                documentId = document.id,
                runId = runId,
                eventId = eventId,
                sourceHash = actualHash,
                configuration = configuration,
                createdAt = createdAt,
            )
        }

        return CreatedAnalysisRunResponse(
            documentId = document.id,
            analysisRunId = runId,
            filename = document.filename,
            sourceContentSha256 = actualHash,
            status = "QUEUED",
            createdAt = createdAt,
        )
    }

    fun get(runId: UUID): AnalysisRunSummary? = analysisRunRepository.findSummary(runId)
        ?.let { it.copy(pipeline = pipelineProgressRepository.find(runId)) }

    fun getParsedDocument(runId: UUID): ParsedDocumentView {
        if (get(runId) == null) throw ResponseStatusException(HttpStatus.NOT_FOUND, "Analysis Run not found.")
        return parsedDocumentRepository.find(runId)
            ?: throw ResponseStatusException(HttpStatus.CONFLICT, "Parsed document structure is not available until parsing completes.")
    }

    fun getSourcePdfAccess(runId: UUID): AnalysisRunSourcePdfAccess {
        val source = analysisRunRepository.findSourcePdf(runId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Analysis Run not found.")

        if (source.documentSha256 != source.runSourceSha256 || source.objectKey != "source/${source.documentId}/${source.runSourceSha256}.pdf") {
            throw ResponseStatusException(HttpStatus.CONFLICT, "The stored Source Document does not match this Analysis Run.")
        }

        val metadata = try {
            objectStore.stat(source.objectKey)
        } catch (exception: Exception) {
            logger.atWarn()
                .addKeyValue("analysisRunId", runId)
                .addKeyValue("errorType", exception.javaClass.simpleName)
                .log("Stored Source Document unavailable for viewing")
            throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "The stored Source Document is temporarily unavailable.")
        }

        if (metadata.sha256 != null && metadata.sha256 != source.runSourceSha256) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "The stored Source Document does not match this Analysis Run.")
        }

        if (metadata.sha256 == null) verifyLegacySourceObject(runId, source, metadata)

        val viewDisposition = ContentDisposition.inline().filename(source.filename, StandardCharsets.UTF_8).build().toString()
        val downloadDisposition = ContentDisposition.attachment().filename(source.filename, StandardCharsets.UTF_8).build().toString()
        val viewUrl: String
        val downloadUrl: String
        try {
            viewUrl = objectStore.presignGet(source.objectKey, viewDisposition, SOURCE_PDF_PRESIGNED_URL_EXPIRY_SECONDS)
            downloadUrl = objectStore.presignGet(source.objectKey, downloadDisposition, SOURCE_PDF_PRESIGNED_URL_EXPIRY_SECONDS)
        } catch (exception: Exception) {
            logger.atWarn()
                .addKeyValue("analysisRunId", runId)
                .addKeyValue("errorType", exception.javaClass.simpleName)
                .log("Could not create temporary Source Document links")
            throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "The original Source Document is temporarily unavailable.")
        }

        return AnalysisRunSourcePdfAccess(
            filename = source.filename,
            viewUrl = viewUrl,
            downloadUrl = downloadUrl,
            expiresAt = Instant.now().plusSeconds(SOURCE_PDF_PRESIGNED_URL_EXPIRY_SECONDS.toLong()),
        )
    }

    private fun verifyLegacySourceObject(
        runId: UUID,
        source: AnalysisRunRepository.StoredRunSourceDocument,
        metadata: SourceObjectMetadata,
    ) {
        val content = try {
            objectStore.get(source.objectKey)
        } catch (exception: Exception) {
            logger.atWarn()
                .addKeyValue("analysisRunId", runId)
                .addKeyValue("errorType", exception.javaClass.simpleName)
                .log("Legacy Source Document unavailable for integrity verification")
            throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "The stored Source Document is temporarily unavailable.")
        }

        if (metadata.size != content.size.toLong() || sha256Hex(content) != source.runSourceSha256) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "The stored Source Document does not match this Analysis Run.")
        }
    }

    fun list(limit: Int = 25, cursorToken: String? = null, query: String? = null, status: String? = null): AnalysisRunPage {
        val pageSize = limit.coerceIn(1, 100)
        val cursor = cursorToken?.let(AnalysisRunCursorCodec::decode)
        val filenameQuery = query?.trim()?.takeIf(String::isNotEmpty)
        if (filenameQuery != null && filenameQuery.length > MAX_FILENAME_QUERY_LENGTH) {
            throw IllegalArgumentException("Analysis Run filename search must be at most $MAX_FILENAME_QUERY_LENGTH characters.")
        }
        if (status != null && status !in RUN_STATUSES) {
            throw IllegalArgumentException("Analysis Run status filter is invalid.")
        }
        val rows = analysisRunRepository.list(pageSize, cursor, filenameQuery, status)
        val first = rows.items.firstOrNull()
        val last = rows.items.lastOrNull()
        return AnalysisRunPage(
            items = rows.items,
            nextCursor = last?.takeIf { rows.hasNext }
                ?.let { AnalysisRunCursorCodec.encode(it.createdAt, it.id, Direction.NEXT) },
            previousCursor = first?.takeIf { rows.hasPrevious }
                ?.let { AnalysisRunCursorCodec.encode(it.createdAt, it.id, Direction.PREVIOUS) },
        )
    }

    private fun insertRunAndOutbox(
        documentId: UUID,
        runId: UUID,
        eventId: UUID,
        sourceHash: String,
        configuration: AnalysisConfigurationSnapshot,
        createdAt: Instant,
    ) {
        try {
            sourceDocumentRepository.lockActiveSourceDocument(documentId)
        } catch (_: SourceDocumentDeletedException) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Source Document not found.")
        }
        analysisRunRepository.insertQueuedRun(
            runId = runId,
            documentId = documentId,
            sourceHash = sourceHash,
            parserId = parserId,
            parserVersion = parserVersion,
            configurationJson = configurationFactory.toJson(configuration),
            createdAt = createdAt,
        )
        pipelineProgressRepository.initializeRun(runId)
        analysisRunExecutionRepository.insertRecordingBestEffort(runId, configuration.captureExecution, createdAt)
        val event = PipelineEvent(
            eventId = eventId,
            eventType = DOCUMENT_ANALYSIS_REQUESTED,
            schemaVersion = 1,
            analysisRunId = runId,
            correlationId = runId,
            causationId = null,
            occurredAt = createdAt,
            attempt = 0,
            payload = DocumentAnalysisRequestedPayload(documentId, sourceHash),
            traceparent = W3CTraceContext.forTraceId(runId),
        )
        outboxRepository.insert(event, createdAt)
    }

    companion object {
        private const val MAX_FILENAME_QUERY_LENGTH = 200
        private const val SOURCE_PDF_PRESIGNED_URL_EXPIRY_SECONDS = 6 * 60 * 60
        private val RUN_STATUSES = setOf("QUEUED", "PROCESSING", "PARSED", "COMPLETED", "COMPLETED_WITH_WARNINGS", "FAILED")
        private val logger = LoggerFactory.getLogger(AnalysisRunService::class.java)
    }
}
