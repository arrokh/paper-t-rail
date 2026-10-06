package com.papertrail.api.document.repository

import com.papertrail.api.document.domain.SourceDocumentDeletedException
import com.papertrail.api.document.validation.ValidatedPdf
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@Repository
class SourceDocumentRepository(
    private val jdbc: JdbcTemplate,
) {
    fun insert(
        documentId: UUID,
        objectKey: String,
        pdf: ValidatedPdf,
        parserId: String,
        parserVersion: String,
        createdAt: Instant,
    ) {
        jdbc.update(
            """
            INSERT INTO source_documents (
                id, filename, content_type, object_key, sha256, language,
                page_count, extracted_character_count, parser_id, parser_version, created_at
            ) VALUES (?, ?, 'application/pdf', ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            documentId,
            pdf.sanitizedFilename,
            objectKey,
            pdf.sha256,
            pdf.language,
            pdf.pageCount,
            pdf.extractedCharacterCount,
            parserId,
            parserVersion,
            Timestamp.from(createdAt),
        )
    }

    fun findActive(documentId: UUID): StoredSourceDocument? = jdbc.query(
        """
        SELECT document.id, document.filename, document.object_key, document.sha256
          FROM source_documents document
         WHERE document.id = ?
           AND NOT EXISTS (
               SELECT 1 FROM source_document_tombstones tombstone WHERE tombstone.document_id = document.id
           )
        """.trimIndent(),
        { rs, _ ->
            StoredSourceDocument(
                id = rs.getObject("id", UUID::class.java),
                filename = rs.getString("filename"),
                objectKey = rs.getString("object_key"),
                sha256 = rs.getString("sha256"),
            )
        },
        documentId,
    ).firstOrNull()

    fun isDeleted(documentId: UUID): Boolean = jdbc.queryForObject(
        "SELECT EXISTS (SELECT 1 FROM source_document_tombstones WHERE document_id = ?)",
        Boolean::class.java,
        documentId,
    ) == true

    fun lockActiveSourceDocument(documentId: UUID) {
        val locked = jdbc.query(
            """
            SELECT document.id
              FROM source_documents document
             WHERE document.id = ?
               AND NOT EXISTS (
                   SELECT 1 FROM source_document_tombstones tombstone WHERE tombstone.document_id = document.id
               )
             FOR UPDATE
            """.trimIndent(),
            { rs, _ -> rs.getObject("id", UUID::class.java) },
            documentId,
        ).firstOrNull()
        if (locked == null) throw SourceDocumentDeletedException()
    }

    fun lockActiveAnalysisRun(analysisRunId: UUID) {
        val documentId = jdbc.query(
            "SELECT document_id FROM analysis_runs WHERE id = ?",
            { rs, _ -> rs.getObject("document_id", UUID::class.java) },
            analysisRunId,
        ).firstOrNull() ?: throw SourceDocumentDeletedException()
        lockActiveSourceDocument(documentId)
    }

    fun requireActiveSourceDocument(documentId: UUID) {
        val active = jdbc.queryForObject(
            """
            SELECT EXISTS (
                SELECT 1
                  FROM source_documents document
                 WHERE document.id = ?
                   AND NOT EXISTS (
                       SELECT 1 FROM source_document_tombstones tombstone WHERE tombstone.document_id = document.id
                   )
            )
            """.trimIndent(),
            Boolean::class.java,
            documentId,
        ) == true
        if (!active) throw SourceDocumentDeletedException()
    }

    fun requireActiveAnalysisRun(analysisRunId: UUID): UUID {
        val documentId = jdbc.query(
            "SELECT document_id FROM analysis_runs WHERE id = ?",
            { rs, _ -> rs.getObject("document_id", UUID::class.java) },
            analysisRunId,
        ).firstOrNull() ?: throw SourceDocumentDeletedException()
        requireActiveSourceDocument(documentId)
        return documentId
    }

    data class StoredSourceDocument(
        val id: UUID,
        val filename: String,
        val objectKey: String,
        val sha256: String,
    )
}
