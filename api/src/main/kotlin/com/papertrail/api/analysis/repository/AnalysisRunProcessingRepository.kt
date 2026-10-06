package com.papertrail.api.analysis.repository

import com.papertrail.api.utils.JsonUtil
import com.papertrail.api.analysis.configuration.AnalysisConfigurationSnapshot
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class AnalysisRunProcessingRepository(
    private val jdbc: JdbcTemplate,
) {
    fun sourceObject(documentId: UUID): StoredSource? = jdbc.query(
        "SELECT object_key, sha256 FROM source_documents WHERE id = ?",
        { rs, _ -> StoredSource(rs.getString("object_key"), rs.getString("sha256")) },
        documentId,
    ).firstOrNull()

    fun runProvenance(analysisRunId: UUID): ProcessingRun? = jdbc.query(
        """
        SELECT document_id, source_content_sha256, source_parser_id, source_parser_version,
               configuration_snapshot::text AS configuration_snapshot
          FROM analysis_runs WHERE id = ?
        """.trimIndent(),
        { rs, _ ->
            ProcessingRun(
                documentId = rs.getObject("document_id", UUID::class.java),
                sourceHash = rs.getString("source_content_sha256"),
                parserId = rs.getString("source_parser_id"),
                parserVersion = rs.getString("source_parser_version"),
                configuration = JsonUtil.fromJson(
                    rs.getString("configuration_snapshot"),
                    AnalysisConfigurationSnapshot::class.java,
                ),
            )
        },
        analysisRunId,
    ).firstOrNull()

    fun queueRun(analysisRunId: UUID): QueueRun? = jdbc.query(
        "SELECT document_id, source_content_sha256, status FROM analysis_runs WHERE id = ?",
        { rs, _ ->
            QueueRun(
                documentId = rs.getObject("document_id", UUID::class.java),
                sourceHash = rs.getString("source_content_sha256"),
                status = rs.getString("status"),
            )
        },
        analysisRunId,
    ).firstOrNull()

    fun markProcessing(
        analysisRunId: UUID,
        documentId: UUID,
        sourceHash: String,
    ): Boolean = jdbc.update(
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
        analysisRunId,
        documentId,
        sourceHash,
    ) == 1

    fun advanceToReferenceResolution(analysisRunId: UUID, documentId: UUID, sourceHash: String): Boolean = jdbc.update(
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
        analysisRunId,
        documentId,
        sourceHash,
    ) == 1

    fun bibliographyWorkItems(analysisRunId: UUID): List<BibliographyWorkItem> = jdbc.query(
        "SELECT id, local_reference_key FROM bibliography_entries WHERE analysis_run_id = ? ORDER BY entry_order",
        { rs, _ -> BibliographyWorkItem(rs.getObject("id", UUID::class.java), rs.getString("local_reference_key")) },
        analysisRunId,
    )

    fun accessConfigured(analysisRunId: UUID): Boolean = jdbc.queryForObject(
        "SELECT jsonb_typeof(configuration_snapshot -> 'openAccess') = 'object' FROM analysis_runs WHERE id = ?",
        Boolean::class.java,
        analysisRunId,
    ) == true

    fun verificationConfigured(analysisRunId: UUID): Boolean = jdbc.queryForObject(
        """
        SELECT analysis_run_has_conflict_aware_evidence_coverage(configuration_snapshot)
            OR (configuration_snapshot #>> '{systemOne,provider}' <> 'mock'
                AND configuration_snapshot #>> '{aggregation,executionStatus}' IN ('NOT_RUN', 'PENDING'))
          FROM analysis_runs
         WHERE id = ?
        """.trimIndent(),
        Boolean::class.java,
        analysisRunId,
    ) == true

    fun evidenceRetrievalConfigured(analysisRunId: UUID): Boolean = jdbc.queryForObject(
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

    fun updateFailureReasonIfProcessing(analysisRunId: UUID, failureReason: String) {
        jdbc.update(
            "UPDATE analysis_runs SET failure_reason = COALESCE(failure_reason, ?), updated_at = now() WHERE id = ? AND status = 'PROCESSING'",
            failureReason,
            analysisRunId,
        )
    }

    fun failQueuedRun(
        analysisRunId: UUID,
        documentId: UUID,
        sourceHash: String,
        eventId: UUID,
        reason: String,
    ) {
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
            analysisRunId,
            documentId,
            sourceHash,
            eventId,
        )
    }

    fun failProcessingRun(
        analysisRunId: UUID,
        documentId: UUID,
        sourceHash: String,
        eventId: UUID,
        reason: String,
    ): Int = jdbc.update(
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
        analysisRunId,
        documentId,
        sourceHash,
        eventId,
    )

    data class StoredSource(val objectKey: String, val sha256: String)

    data class ProcessingRun(
        val documentId: UUID,
        val sourceHash: String,
        val parserId: String,
        val parserVersion: String,
        val configuration: AnalysisConfigurationSnapshot,
    )

    data class QueueRun(val documentId: UUID, val sourceHash: String, val status: String)
    data class BibliographyWorkItem(val id: UUID, val referenceKey: String)
}
