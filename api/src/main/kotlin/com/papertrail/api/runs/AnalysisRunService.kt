package com.papertrail.api.runs

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.papertrail.api.documents.PdfDocumentValidator
import com.papertrail.api.documents.sha256Hex
import com.papertrail.api.queue.DOCUMENT_ANALYSIS_REQUESTED
import com.papertrail.api.queue.DocumentAnalysisRequestedPayload
import com.papertrail.api.queue.PipelineEvent
import com.papertrail.api.storage.SourceDocumentObjectStore
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.server.ResponseStatusException
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
    private val objectMapper: ObjectMapper,
    @Value("\${paper-trail.validation.parser-id}") private val parserId: String,
    @Value("\${paper-trail.validation.parser-version}") private val parserVersion: String,
) {
    fun maxUploadBytes(): Long = validator.limits.maxBytes

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
                    parserId,
                    parserVersion,
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
                logger.warn("Database commit outcome is uncertain; keeping the uploaded source object")
            } else {
                runCatching { objectStore.delete(objectKey) }
                    .onFailure { cleanupError ->
                        logger.error("Failed to clean up an unreferenced source object after database failure")
                        logger.debug("Object cleanup error", cleanupError)
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
        """.trimIndent(),
        { resultSet, _ -> resultSet.toRunSummary() },
        runId,
    ).firstOrNull()

    fun list(limit: Int = 25): List<AnalysisRunSummary> = jdbc.query(
        """
        SELECT r.id, r.document_id, d.filename, r.source_content_sha256, r.status,
               r.progress::text AS progress, r.configuration_snapshot::text AS configuration,
               r.created_at, r.started_at, r.failure_reason
          FROM analysis_runs r
          JOIN source_documents d ON d.id = r.document_id
         ORDER BY r.created_at DESC, r.id DESC
         LIMIT ?
        """.trimIndent(),
        { resultSet, _ -> resultSet.toRunSummary() },
        limit.coerceIn(1, 100),
    )

    private fun insertRunAndOutbox(
        documentId: UUID,
        runId: UUID,
        eventId: UUID,
        sourceHash: String,
        configuration: AnalysisConfigurationSnapshot,
        createdAt: Instant,
    ) {
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

    private fun findDocument(id: UUID): StoredDocument? = jdbc.query(
        "SELECT id, filename, object_key, sha256 FROM source_documents WHERE id = ?",
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

    companion object {
        private val logger = LoggerFactory.getLogger(AnalysisRunService::class.java)
    }
}
