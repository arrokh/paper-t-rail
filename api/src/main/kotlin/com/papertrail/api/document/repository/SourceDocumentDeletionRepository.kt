package com.papertrail.api.document.repository

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class SourceDocumentDeletionRepository(
    private val jdbc: JdbcTemplate,
) {
    fun markDeleted(documentId: UUID): TombstoneResult {
        val sourceDocumentExists = jdbc.query(
            "SELECT id FROM source_documents WHERE id = ? FOR UPDATE",
            { rs, _ -> rs.getObject("id", UUID::class.java) },
            documentId,
        ).isNotEmpty()
        if (!sourceDocumentExists) {
            val alreadyDeleted = jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM source_document_tombstones WHERE document_id = ?)",
                Boolean::class.java,
                documentId,
            ) == true
            return if (alreadyDeleted) TombstoneResult.ALREADY_DELETED else TombstoneResult.NOT_FOUND
        }

        jdbc.update(
            "INSERT INTO source_document_tombstones (document_id) VALUES (?) ON CONFLICT (document_id) DO NOTHING",
            documentId,
        )
        return TombstoneResult.MARKED
    }

    fun lockDocumentForPurge(documentId: UUID): Boolean = jdbc.query(
        "SELECT id FROM source_documents WHERE id = ? FOR UPDATE",
        { rs, _ -> rs.getObject("id", UUID::class.java) },
        documentId,
    ).isNotEmpty()

    fun objectKeysToDelete(documentId: UUID): List<String> = jdbc.query(
        """
        SELECT object_key
          FROM source_documents
         WHERE id = ?
        UNION
        SELECT parsed.raw_tei_object_key
          FROM parsed_document_parses parsed
          JOIN analysis_runs run ON run.id = parsed.analysis_run_id
         WHERE run.document_id = ?
           AND parsed.raw_tei_object_key IS NOT NULL
        UNION
        SELECT access.object_key
          FROM cited_paper_access access
          JOIN analysis_runs run ON run.id = access.analysis_run_id
         WHERE run.document_id = ?
           AND access.object_key IS NOT NULL
           AND NOT EXISTS (
               SELECT 1
                 FROM cited_paper_access shared_access
                 JOIN analysis_runs shared_run ON shared_run.id = shared_access.analysis_run_id
                 LEFT JOIN source_document_tombstones tombstone
                   ON tombstone.document_id = shared_run.document_id
                WHERE shared_access.object_key = access.object_key
                  AND shared_run.document_id <> ?
                  AND tombstone.document_id IS NULL
           )
        UNION
        SELECT upload.staging_object_key
          FROM recovery_batch_uploads upload
          JOIN analysis_runs run ON run.id = upload.analysis_run_id
         WHERE run.document_id = ?
        UNION
        SELECT upload.finalized_object_key
          FROM recovery_batch_uploads upload
          JOIN analysis_runs run ON run.id = upload.analysis_run_id
         WHERE run.document_id = ?
        """.trimIndent(),
        { rs, _ -> rs.getString("object_key") },
        documentId,
        documentId,
        documentId,
        documentId,
        documentId,
        documentId,
    ).distinct()

    fun canonicalPaperIds(documentId: UUID): List<UUID> = jdbc.query(
        """
        SELECT DISTINCT resolution.canonical_paper_id
          FROM bibliography_entry_resolutions resolution
          JOIN analysis_runs run ON run.id = resolution.analysis_run_id
         WHERE run.document_id = ?
           AND resolution.canonical_paper_id IS NOT NULL
        """.trimIndent(),
        { rs, _ -> rs.getObject(1, UUID::class.java) },
        documentId,
    )

    fun deleteInboxEvents(documentId: UUID) {
        jdbc.update(
            """
            DELETE FROM inbox_events
             WHERE event_id IN (
                 SELECT event.event_id
                   FROM outbox_events event
                   JOIN analysis_runs run ON run.id = event.analysis_run_id
                  WHERE run.document_id = ?
             )
            """.trimIndent(),
            documentId,
        )
    }

    fun deleteOutboxEvents(documentId: UUID) {
        jdbc.update(
            "DELETE FROM outbox_events WHERE analysis_run_id IN (SELECT id FROM analysis_runs WHERE document_id = ?)",
            documentId,
        )
    }

    fun deleteAnalysisRuns(documentId: UUID) {
        jdbc.update("DELETE FROM analysis_runs WHERE document_id = ?", documentId)
    }

    fun deleteSourceDocument(documentId: UUID) {
        jdbc.update("DELETE FROM source_documents WHERE id = ?", documentId)
    }

    fun deleteUnreferencedCanonicalPaper(canonicalPaperId: UUID) {
        jdbc.update(
            """
            DELETE FROM canonical_papers paper
             WHERE paper.id = ?
               AND NOT EXISTS (
                   SELECT 1 FROM bibliography_entry_resolutions resolution
                    WHERE resolution.canonical_paper_id = paper.id
               )
            """.trimIndent(),
            canonicalPaperId,
        )
    }

    enum class TombstoneResult {
        MARKED,
        ALREADY_DELETED,
        NOT_FOUND,
    }
}
