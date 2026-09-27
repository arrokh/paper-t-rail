package com.papertrail.api.document.service

import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID

class SourceDocumentDeletedException : IllegalStateException("Source Document has been deleted.")

fun JdbcTemplate.isSourceDocumentDeleted(documentId: UUID): Boolean = queryForObject(
    "SELECT EXISTS (SELECT 1 FROM source_document_tombstones WHERE document_id = ?)",
    Boolean::class.java,
    documentId,
) == true

fun JdbcTemplate.lockActiveSourceDocument(documentId: UUID) {
    val locked = query(
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

fun JdbcTemplate.lockActiveAnalysisRun(analysisRunId: UUID) {
    val documentId = query(
        "SELECT document_id FROM analysis_runs WHERE id = ?",
        { rs, _ -> rs.getObject("document_id", UUID::class.java) },
        analysisRunId,
    ).firstOrNull() ?: throw SourceDocumentDeletedException()
    lockActiveSourceDocument(documentId)
}

fun JdbcTemplate.requireActiveSourceDocument(documentId: UUID) {
    val active = queryForObject(
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

fun JdbcTemplate.requireActiveAnalysisRun(analysisRunId: UUID): UUID {
    val documentId = query(
        "SELECT document_id FROM analysis_runs WHERE id = ?",
        { rs, _ -> rs.getObject("document_id", UUID::class.java) },
        analysisRunId,
    ).firstOrNull() ?: throw SourceDocumentDeletedException()
    requireActiveSourceDocument(documentId)
    return documentId
}
