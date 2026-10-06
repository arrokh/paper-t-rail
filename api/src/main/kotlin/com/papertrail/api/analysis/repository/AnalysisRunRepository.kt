package com.papertrail.api.analysis.repository

import com.fasterxml.jackson.databind.ObjectMapper
import com.papertrail.api.analysis.http.AnalysisRunSummary
import com.papertrail.api.analysis.pagination.AnalysisRunCursor
import com.papertrail.api.analysis.pagination.Direction
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@Repository
class AnalysisRunRepository(
    private val jdbc: JdbcTemplate,
    private val objectMapper: ObjectMapper,
) {
    fun insertQueuedRun(
        runId: UUID,
        documentId: UUID,
        sourceHash: String,
        parserId: String,
        parserVersion: String,
        configurationJson: String,
        createdAt: Instant,
    ) {
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
            configurationJson,
            """{"stage":"QUEUED","percent":0,"message":"Waiting for worker."}""",
            Timestamp.from(createdAt),
        )
    }

    fun findSummary(runId: UUID): AnalysisRunSummary? = jdbc.query(
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
    ).firstOrNull()

    fun findSourcePdf(runId: UUID): StoredRunSourceDocument? = jdbc.query(
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
    ).firstOrNull()

    internal fun list(
        pageSize: Int,
        cursor: AnalysisRunCursor?,
        filenameQuery: String?,
        status: String?,
    ): AnalysisRunPageRows {
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
            { resultSet, _ -> resultSet.toRunSummary() },
            *queryParameters.toTypedArray(),
        )
        val items = if (cursor?.direction == Direction.PREVIOUS) queriedRows.asReversed() else queriedRows
        val first = items.firstOrNull()
        val last = items.lastOrNull()
        val hasPrevious = first != null && cursor != null && existsAtBoundary(baseQuery, parameters, first, ">")
        val hasNext = last != null && existsAtBoundary(baseQuery, parameters, last, "<")
        return AnalysisRunPageRows(
            items = items,
            hasPrevious = hasPrevious,
            hasNext = hasNext,
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

    data class AnalysisRunPageRows(
        val items: List<AnalysisRunSummary>,
        val hasPrevious: Boolean,
        val hasNext: Boolean,
    )

    data class StoredRunSourceDocument(
        val documentId: UUID,
        val filename: String,
        val objectKey: String,
        val documentSha256: String,
        val runSourceSha256: String,
    )
}
