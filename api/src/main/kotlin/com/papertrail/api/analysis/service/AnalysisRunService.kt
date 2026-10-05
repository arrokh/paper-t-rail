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
import com.papertrail.api.analysis.queue.DOCUMENT_ANALYSIS_REQUESTED
import com.papertrail.api.analysis.queue.DocumentAnalysisRequestedPayload
import com.papertrail.api.citation.parsing.ParsedDocumentRepository
import com.papertrail.api.citation.parsing.ParsedDocumentView
import com.papertrail.api.document.validation.PdfDocumentValidator
import com.papertrail.api.document.service.SourceDocumentDeletedException
import com.papertrail.api.document.service.lockActiveSourceDocument
import com.papertrail.api.document.storage.SourceDocumentObjectStore
import com.papertrail.api.document.storage.SourceObjectMetadata
import com.papertrail.api.infrastructure.crypto.sha256Hex
import com.papertrail.api.infrastructure.messaging.events.PipelineEvent
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.ConnectionCallback
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.server.ResponseStatusException
import java.nio.charset.StandardCharsets
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@Service
class AnalysisRunService(
    private val jdbc: JdbcTemplate,
    private val transactionTemplate: TransactionTemplate,
    private val validator: PdfDocumentValidator,
    private val objectStore: SourceDocumentObjectStore,
    private val configurationFactory: RunConfigurationFactory,
    private val parsedDocumentRepository: ParsedDocumentRepository,
    private val objectMapper: ObjectMapper,
    @Value("\${paper-trail.analysis.parser-id}") private val parserId: String,
    @Value("\${paper-trail.analysis.parser-version}") private val parserVersion: String,
    private val pipelineProgressRepository: AnalysisRunPipelineProgressRepository = AnalysisRunPipelineProgressRepository(jdbc),
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
                jdbc.update(
                    """
                    INSERT INTO source_documents (
                        id, filename, content_type, object_key, sha256, language,
                        page_count, extracted_character_count, parser_id, parser_version, created_at
                    ) VALUES (?, ?, 'application/pdf', ?, ?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent(),
                    documentId,
                    validPdf.sanitizedFilename,
                    objectKey,
                    validPdf.sha256,
                    validPdf.language,
                    validPdf.pageCount,
                    validPdf.extractedCharacterCount,
                    validator.parserId,
                    validator.parserVersion,
                    Timestamp.from(createdAt),
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
        val document = findDocument(documentId) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Source Document not found.")
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

    fun get(runId: UUID): AnalysisRunSummary? = jdbc.query(
        """
        SELECT r.id, r.document_id, d.filename, r.source_content_sha256, r.status,
               r.progress::text AS progress, r.configuration_snapshot::text AS configuration,
               r.created_at, r.started_at, r.failure_reason
          FROM analysis_runs r
          JOIN source_documents d ON d.id = r.document_id
         WHERE r.id = ?
           AND NOT EXISTS (SELECT 1 FROM source_document_tombstones tombstone WHERE tombstone.document_id = d.id)
        """.trimIndent(),
        { resultSet, _ -> resultSet.toRunSummary() },
        runId,
    ).firstOrNull()?.let { it.copy(pipeline = pipelineProgressRepository.find(runId)) }

    fun getParsedDocument(runId: UUID): ParsedDocumentView {
        if (get(runId) == null) throw ResponseStatusException(HttpStatus.NOT_FOUND, "Analysis Run not found.")
        return parsedDocumentRepository.find(runId)
            ?: throw ResponseStatusException(HttpStatus.CONFLICT, "Parsed document structure is not available until parsing completes.")
    }

    fun getSourcePdfAccess(runId: UUID): AnalysisRunSourcePdfAccess {
        val source = jdbc.query(
            """
            SELECT document.id AS document_id, document.filename, document.object_key, document.sha256 AS document_sha256,
                   run.source_content_sha256
              FROM analysis_runs run
              JOIN source_documents document ON document.id = run.document_id
             WHERE run.id = ?
               AND NOT EXISTS (
                   SELECT 1 FROM source_document_tombstones tombstone WHERE tombstone.document_id = document.id
               )
            """.trimIndent(),
            { resultSet, _ ->
                StoredRunSourceDocument(
                    documentId = resultSet.getObject("document_id", UUID::class.java),
                    filename = resultSet.getString("filename"),
                    objectKey = resultSet.getString("object_key"),
                    documentSha256 = resultSet.getString("document_sha256"),
                    runSourceSha256 = resultSet.getString("source_content_sha256"),
                )
            },
            runId,
        ).firstOrNull() ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Analysis Run not found.")

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

    private fun verifyLegacySourceObject(runId: UUID, source: StoredRunSourceDocument, metadata: SourceObjectMetadata) {
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
        val where = mutableListOf(
            "NOT EXISTS (SELECT 1 FROM source_document_tombstones tombstone WHERE tombstone.document_id = d.id)",
        )
        val parameters = mutableListOf<Any>()
        status?.let {
            where += "r.status = ?"
            parameters += it
        }
        filenameQuery?.let {
            where += "d.filename ILIKE ? ESCAPE '!'"
            parameters += "%${it.replace("!", "!!").replace("%", "!%").replace("_", "!_")}%"
        }
        val baseQuery = """
            FROM analysis_runs r
            JOIN source_documents d ON d.id = r.document_id
            WHERE ${where.joinToString(" AND ")}
        """.trimIndent()
        val mapper = { resultSet: ResultSet, _: Int -> resultSet.toRunSummary() }
        val cursorClause = when (cursor?.direction) {
            Direction.NEXT -> "AND (r.created_at, r.id) < (?, ?)"
            Direction.PREVIOUS -> "AND (r.created_at, r.id) > (?, ?)"
            null -> ""
        }
        val queryParameters = parameters.toMutableList()
        if (cursor != null) {
            queryParameters += Timestamp.from(cursor.createdAt)
            queryParameters += cursor.id
        }
        queryParameters += pageSize
        val order = if (cursor?.direction == Direction.PREVIOUS) "ASC" else "DESC"
        val queriedRows = jdbc.query(
            """
            SELECT r.id, r.document_id, d.filename, r.source_content_sha256, r.status,
                   r.progress::text AS progress, r.configuration_snapshot::text AS configuration,
                   r.created_at, r.started_at, r.failure_reason
              $baseQuery
              $cursorClause
             ORDER BY r.created_at $order, r.id $order
             LIMIT ?
            """.trimIndent(),
            mapper,
            *queryParameters.toTypedArray(),
        )
        val items = if (cursor?.direction == Direction.PREVIOUS) queriedRows.asReversed() else queriedRows
        val first = items.firstOrNull()
        val last = items.lastOrNull()
        val hasPrevious = first != null && cursor != null && existsAtBoundary(baseQuery, parameters, first, ">")
        val hasNext = last != null && existsAtBoundary(baseQuery, parameters, last, "<")
        return AnalysisRunPage(
            items = items,
            nextCursor = last?.takeIf { hasNext }?.let { AnalysisRunCursorCodec.encode(it.createdAt, it.id, Direction.NEXT) },
            previousCursor = first?.takeIf { hasPrevious }?.let { AnalysisRunCursorCodec.encode(it.createdAt, it.id, Direction.PREVIOUS) },
        )
    }

    private fun existsAtBoundary(baseQuery: String, parameters: List<Any>, run: AnalysisRunSummary, comparison: String): Boolean {
        require(comparison == "<" || comparison == ">")
        val existsParameters = parameters + listOf(Timestamp.from(run.createdAt), run.id)
        return jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 $baseQuery AND (r.created_at, r.id) $comparison (?, ?))",
            Boolean::class.java,
            *existsParameters.toTypedArray(),
        ) == true
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
            jdbc.lockActiveSourceDocument(documentId)
        } catch (_: SourceDocumentDeletedException) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Source Document not found.")
        }
        val snapshotJson = configurationFactory.toJson(configuration)
        jdbc.update(
            """
            INSERT INTO analysis_runs (
                id, document_id, source_content_sha256, source_parser_id, source_parser_version,
                configuration_snapshot, status, progress, created_at
            ) VALUES (?, ?, ?, ?, ?, ?::jsonb, 'QUEUED', ?::jsonb, ?)
            """.trimIndent(),
            runId,
            documentId,
            sourceHash,
            parserId,
            parserVersion,
            snapshotJson,
            """{"stage":"QUEUED","percent":0,"message":"Waiting for worker."}""",
            Timestamp.from(createdAt),
        )
        pipelineProgressRepository.initializeRun(runId)
        insertExecutionRecordingBestEffort(runId, configuration.captureExecution, createdAt)
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
        )
        jdbc.update(
            """
            INSERT INTO outbox_events (
                event_id, event_type, schema_version, analysis_run_id,
                correlation_id, occurred_at, payload, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?)
            """.trimIndent(),
            eventId,
            event.eventType,
            event.schemaVersion,
            runId,
            event.correlationId,
            Timestamp.from(createdAt),
            objectMapper.writeValueAsString(event),
            Timestamp.from(createdAt),
        )
    }

    private fun insertExecutionRecordingBestEffort(runId: UUID, captureEnabled: Boolean, startedAt: Instant) {
        jdbc.execute(ConnectionCallback { connection ->
            val savepoint = try {
                connection.setSavepoint()
            } catch (exception: Exception) {
                logExecutionRecordingFailure(runId, exception)
                return@ConnectionCallback null
            }
            try {
                connection.prepareStatement(
                    """
                    INSERT INTO analysis_run_execution (
                        analysis_run_id, trace_id, capture_requested, capture_enabled,
                        recording_state, completeness, started_at
                    ) VALUES (?, ?, ?, ?, 'RECORDING', 'RECORDING', ?)
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, runId)
                    statement.setObject(2, runId)
                    statement.setBoolean(3, captureEnabled)
                    statement.setBoolean(4, captureEnabled)
                    statement.setTimestamp(5, Timestamp.from(startedAt))
                    statement.executeUpdate()
                }
            } catch (exception: Exception) {
                runCatching { connection.rollback(savepoint) }
                logExecutionRecordingFailure(runId, exception)
            } finally {
                runCatching { connection.releaseSavepoint(savepoint) }
            }
            null
        })
    }

    private fun logExecutionRecordingFailure(runId: UUID, exception: Exception) {
        logger.atWarn()
            .addKeyValue("analysisRunId", runId)
            .addKeyValue("errorType", exception.javaClass.simpleName)
            .log("Analysis Run was queued without an execution recording")
    }

    private fun findDocument(id: UUID): StoredDocument? = jdbc.query(
        """
        SELECT document.id, document.filename, document.object_key, document.sha256
          FROM source_documents document
         WHERE document.id = ?
           AND NOT EXISTS (
               SELECT 1 FROM source_document_tombstones tombstone WHERE tombstone.document_id = document.id
           )
        """.trimIndent(),
        { rs, _ -> StoredDocument(rs.getObject("id", UUID::class.java), rs.getString("filename"), rs.getString("object_key"), rs.getString("sha256")) },
        id,
    ).firstOrNull()

    private fun ResultSet.toRunSummary(): AnalysisRunSummary = AnalysisRunSummary(
        id = getObject("id", UUID::class.java),
        documentId = getObject("document_id", UUID::class.java),
        filename = getString("filename"),
        sourceContentSha256 = getString("source_content_sha256"),
        status = getString("status"),
        progress = objectMapper.readTree(getString("progress")),
        configuration = objectMapper.readTree(getString("configuration")),
        createdAt = getTimestamp("created_at").toInstant(),
        startedAt = getTimestamp("started_at")?.toInstant(),
        failureReason = getString("failure_reason"),
    )

    private data class StoredDocument(val id: UUID, val filename: String, val objectKey: String, val sha256: String)

    private data class StoredRunSourceDocument(
        val documentId: UUID,
        val filename: String,
        val objectKey: String,
        val documentSha256: String,
        val runSourceSha256: String,
    )

    companion object {
        private const val MAX_FILENAME_QUERY_LENGTH = 200
        private const val SOURCE_PDF_PRESIGNED_URL_EXPIRY_SECONDS = 6 * 60 * 60
        private val RUN_STATUSES = setOf("QUEUED", "PROCESSING", "PARSED", "COMPLETED", "COMPLETED_WITH_WARNINGS", "FAILED")
        private val logger = LoggerFactory.getLogger(AnalysisRunService::class.java)
    }
}
