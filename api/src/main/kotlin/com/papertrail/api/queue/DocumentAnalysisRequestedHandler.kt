package com.papertrail.api.queue

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.papertrail.api.documents.sha256Hex
import com.papertrail.api.parsing.ParsedDocumentRepository
import com.papertrail.api.parsing.ScientificDocumentParser
import com.papertrail.api.storage.SourceDocumentObjectStore
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.util.UUID

@Component
class DocumentAnalysisRequestedHandler(
    private val jdbc: JdbcTemplate,
    private val transactionTemplate: TransactionTemplate,
    private val objectMapper: ObjectMapper,
    private val objectStore: SourceDocumentObjectStore,
    private val scientificDocumentParser: ScientificDocumentParser,
    private val parsedDocumentRepository: ParsedDocumentRepository,
) {
    fun isProcessed(eventId: UUID): Boolean = jdbc.queryForObject(
        "SELECT EXISTS (SELECT 1 FROM inbox_events WHERE event_id = ?)",
        Boolean::class.java,
        eventId,
    ) == true

    fun handle(serializedEvent: String): UUID {
        val event: PipelineEvent<DocumentAnalysisRequestedPayload> = objectMapper.readValue(serializedEvent)
        require(event.eventType == DOCUMENT_ANALYSIS_REQUESTED) { "Unsupported event type '${event.eventType}'." }
        if (isProcessed(event.eventId)) return event.eventId

        val document = jdbc.query(
            "SELECT object_key, sha256 FROM source_documents WHERE id = ?",
            { rs, _ -> StoredSource(rs.getString("object_key"), rs.getString("sha256")) },
            event.payload.documentId,
        ).firstOrNull() ?: throw IllegalStateException("Queued Source Document is missing.")
        val run = jdbc.query(
            """
            SELECT document_id, source_content_sha256, source_parser_id, source_parser_version
              FROM analysis_runs WHERE id = ?
            """.trimIndent(),
            { rs, _ ->
                RunProvenance(
                    rs.getObject("document_id", UUID::class.java),
                    rs.getString("source_content_sha256"),
                    rs.getString("source_parser_id"),
                    rs.getString("source_parser_version"),
                )
            },
            event.analysisRunId,
        ).firstOrNull() ?: throw IllegalStateException("Queued Analysis Run is missing.")

        if (run.documentId != event.payload.documentId || run.sourceHash != event.payload.sourceContentSha256 || document.sha256 != run.sourceHash) {
            throw IllegalStateException("Queue event provenance does not match the persisted Source Document and Analysis Run.")
        }
        val content = objectStore.get(document.objectKey)
        if (sha256Hex(content) != run.sourceHash) {
            throw IllegalStateException("Stored Source Document failed its SHA-256 integrity check.")
        }

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

        val parsed = scientificDocumentParser.parse(content)
        if (parsed.parserId != run.parserId || parsed.parserVersion != run.parserVersion) {
            throw IllegalStateException("The scientific parser identity does not match the Analysis Run provenance.")
        }
        require(parsed.rawParserOutput.isNotEmpty()) { "The scientific parser returned no raw parser output." }
        val rawTeiObjectKey =
            "source/${event.payload.documentId}/analysis-runs/${event.analysisRunId}/grobid-${sha256Hex(parsed.rawParserOutput)}.xml"
        objectStore.put(rawTeiObjectKey, parsed.rawParserOutput, "application/xml")
        var transactionBodyCompleted = false
        try {
            transactionTemplate.executeWithoutResult {
                val inserted = jdbc.update(
                    "INSERT INTO inbox_events (event_id, handler_name, processed_at) VALUES (?, ?, ?) ON CONFLICT (event_id) DO NOTHING",
                    event.eventId,
                    DOCUMENT_ANALYSIS_HANDLER,
                    java.sql.Timestamp.from(Instant.now()),
                )
                if (inserted == 0) return@executeWithoutResult

                parsedDocumentRepository.save(event.analysisRunId, run.sourceHash, parsed, rawTeiObjectKey)
                val completed = jdbc.update(
                    """
                    UPDATE analysis_runs
                       SET status = 'PARSED',
                           progress = jsonb_build_object(
                               'stage', 'PARSED',
                               'percent', 100,
                               'message', 'Sections, citation contexts, citation markers, and bibliography entries were saved; claim and evidence analysis has not run.',
                               'sectionCount', ?,
                               'citationContextCount', ?,
                               'citationOccurrenceCount', ?,
                               'bibliographyEntryCount', ?
                           ),
                           updated_at = now()
                     WHERE id = ? AND document_id = ? AND source_content_sha256 = ?
                       AND status = 'PROCESSING'
                    """.trimIndent(),
                    parsed.sections.size,
                    parsed.citationContexts.size,
                    parsed.citationContexts.sumOf { it.occurrences.size },
                    parsed.bibliographyEntries.size,
                    event.analysisRunId,
                    event.payload.documentId,
                    run.sourceHash,
                )
                if (completed != 1) throw IllegalStateException("Analysis Run could not be marked PARSED after parsing.")
                transactionBodyCompleted = true
            }
            if (!transactionBodyCompleted) cleanupRawTeiIfUnreferenced(event.analysisRunId, rawTeiObjectKey)
        } catch (exception: Exception) {
            if (!transactionBodyCompleted) cleanupRawTeiIfUnreferenced(event.analysisRunId, rawTeiObjectKey)
            throw exception
        }
        return event.eventId
    }

    fun markFailed(event: PipelineEvent<DocumentAnalysisRequestedPayload>, reason: String) {
        transactionTemplate.executeWithoutResult {
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
    )

    companion object {
        private val logger = LoggerFactory.getLogger(DocumentAnalysisRequestedHandler::class.java)
    }
}
