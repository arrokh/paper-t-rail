package com.papertrail.api.analysis.execution

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.support.TransactionTemplate
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.UUID

@Repository
class AnalysisRunExecutionRepository(
    private val jdbc: JdbcTemplate,
    private val transactionTemplate: TransactionTemplate,
    private val objectMapper: ObjectMapper,
) {
    fun terminalStatus(analysisRunId: UUID): String? = jdbc.query(
        "SELECT status FROM analysis_runs WHERE id = ? AND status IN ('PARSED', 'COMPLETED', 'COMPLETED_WITH_WARNINGS', 'FAILED')",
        { rs, _ -> rs.getString("status") },
        analysisRunId,
    ).firstOrNull()

    fun runExists(analysisRunId: UUID): Boolean = jdbc.queryForObject(
        """
        SELECT EXISTS (
            SELECT 1 FROM analysis_runs run
            JOIN source_documents document ON document.id = run.document_id
            WHERE run.id = ?
              AND NOT EXISTS (SELECT 1 FROM source_document_tombstones tombstone WHERE tombstone.document_id = document.id)
        )
        """.trimIndent(),
        Boolean::class.java,
        analysisRunId,
    ) == true

    fun markGap(analysisRunId: UUID, reason: String) {
        jdbc.update(
            """
            UPDATE analysis_run_execution
               SET gap_reason = COALESCE(gap_reason, ?),
                   completeness = CASE WHEN finished_at IS NULL THEN completeness ELSE 'INCOMPLETE' END
             WHERE analysis_run_id = ?
            """.trimIndent(),
            reason,
            analysisRunId,
        )
    }

    fun causalParentSpanId(analysisRunId: UUID, causationEventId: UUID): UUID? = jdbc.query(
        """
        SELECT id
          FROM analysis_run_execution_spans
         WHERE analysis_run_id = ? AND event_id = ? AND parent_span_id IS NULL
         ORDER BY attempt DESC, started_at DESC, id DESC
         LIMIT 1
        """.trimIndent(),
        { rs, _ -> rs.getObject("id", UUID::class.java) },
        analysisRunId,
        causationEventId,
    ).firstOrNull()

    fun startSpan(analysisRunId: UUID, spec: ExecutionSpanSpec, safeAttributes: String): ExecutionSpanHandle? {
        val id = UUID.randomUUID()
        val operationId = spec.operationId ?: UUID.randomUUID()
        val startedAt = Instant.now()
        val inserted = jdbc.update(
            """
            INSERT INTO analysis_run_execution_spans (
                id, analysis_run_id, parent_span_id, operation_id, event_id, stage_id, kind, name,
                started_at, status, attempt, provider_id, model_id, attributes
            )
            SELECT ?, execution.analysis_run_id, ?, ?, ?, ?, ?, ?, ?, 'RUNNING', ?, ?, ?, ?::jsonb
              FROM analysis_run_execution execution
             WHERE execution.analysis_run_id = ? AND execution.recording_state = 'RECORDING'
             FOR SHARE
            """.trimIndent(),
            id,
            spec.parentSpanId,
            operationId,
            spec.eventId,
            spec.stageId,
            spec.kind,
            spec.name,
            Timestamp.from(startedAt),
            spec.attempt,
            spec.providerId,
            spec.modelId,
            safeAttributes,
            analysisRunId,
        )
        if (inserted != 1) return null
        return ExecutionSpanHandle(
            id = id,
            analysisRunId = analysisRunId,
            operationId = operationId,
            startedAt = startedAt,
            startedNanos = System.nanoTime(),
            stageId = spec.stageId,
            attempt = spec.attempt,
            eventId = spec.eventId,
        )
    }

    fun recordInterval(
        analysisRunId: UUID,
        spec: ExecutionSpanSpec,
        safeAttributes: String,
        startedAt: Instant,
        endedAt: Instant,
    ) {
        val operationId = spec.operationId ?: spec.eventId ?: UUID.randomUUID()
        val parentSpanId = spec.parentSpanId ?: spec.causationEventId?.let { causalParentSpanId(analysisRunId, it) }
        val durationMillis = Duration.between(startedAt, endedAt).toMillis().coerceAtLeast(0)
        jdbc.update(
            """
            INSERT INTO analysis_run_execution_spans (
                id, analysis_run_id, parent_span_id, operation_id, event_id, stage_id, kind, name,
                started_at, ended_at, duration_millis, status, attempt, provider_id, model_id, attributes
            )
            SELECT ?, execution.analysis_run_id, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'SUCCEEDED', ?, ?, ?, ?::jsonb
              FROM analysis_run_execution execution
             WHERE execution.analysis_run_id = ? AND execution.recording_state = 'RECORDING'
            """.trimIndent(),
            UUID.randomUUID(),
            parentSpanId,
            operationId,
            spec.eventId,
            spec.stageId,
            spec.kind,
            spec.name,
            Timestamp.from(startedAt),
            Timestamp.from(endedAt),
            durationMillis,
            spec.attempt,
            spec.providerId,
            spec.modelId,
            safeAttributes,
            analysisRunId,
        )
    }

    fun finishSpan(
        handle: ExecutionSpanHandle,
        status: String,
        errorCode: String?,
        httpStatus: Int?,
    ) {
        val endedAt = Instant.now()
        val durationMillis = ((System.nanoTime() - handle.startedNanos).coerceAtLeast(0L) / 1_000_000L)
        jdbc.update(
            """
            UPDATE analysis_run_execution_spans
               SET ended_at = ?, duration_millis = ?, status = ?, safe_error_code = ?, http_status = ?
             WHERE analysis_run_id = ? AND id = ? AND status = 'RUNNING'
            """.trimIndent(),
            Timestamp.from(endedAt),
            durationMillis,
            status,
            errorCode,
            httpStatus,
            handle.analysisRunId,
            handle.id,
        )
    }

    fun recordArtifact(
        analysisRunId: UUID,
        spanId: UUID,
        artifact: ExecutionSpanArtifactSpec,
        sanitized: SanitizedExecutionArtifact,
        sha256: String?,
    ) {
        transactionTemplate.executeWithoutResult {
            val alreadyRemoved = jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM analysis_run_execution_span_artifacts WHERE analysis_run_id = ? AND span_id = ? AND role = ? AND fidelity = 'REMOVED')",
                Boolean::class.java,
                analysisRunId,
                spanId,
                artifact.role,
            ) == true
            if (alreadyRemoved) return@executeWithoutResult
            val recording = jdbc.query(
                "SELECT capture_enabled, recording_state FROM analysis_run_execution WHERE analysis_run_id = ? FOR UPDATE",
                { rs, _ -> rs.getBoolean("capture_enabled") to rs.getString("recording_state") },
                analysisRunId,
            ).firstOrNull() ?: return@executeWithoutResult
            if (!recording.first || recording.second != "RECORDING") {
                insertAssociation(analysisRunId, spanId, artifact.role, null, CaptureFidelity.OMITTED.name, "CAPTURE_DISABLED", artifact.schemaVersion, null, null)
                return@executeWithoutResult
            }
            if (sanitized.fidelity == CaptureFidelity.OMITTED || sanitized.content == null || sha256 == null) {
                insertAssociation(analysisRunId, spanId, artifact.role, null, sanitized.fidelity.name, sanitized.reason, artifact.schemaVersion, null, null)
                return@executeWithoutResult
            }
            val sizeBytes = sanitized.content.toByteArray(Charsets.UTF_8).size
            val artifactId = UUID.randomUUID()
            val persisted = jdbc.query(
                """
                INSERT INTO analysis_run_execution_artifacts (
                    id, analysis_run_id, content_sha256, media_type, content,
                    schema_version, capture_version, sanitizer_version, size_bytes
                ) VALUES (?, ?, ?, 'application/json', ?, ?, 'execution-capture-v1', 'allowlist-sanitizer-v1', ?)
                ON CONFLICT (analysis_run_id, content_sha256, schema_version) DO UPDATE
                   SET content_sha256 = EXCLUDED.content_sha256
                RETURNING id, content IS NOT NULL AS content_available
                """.trimIndent(),
                { rs, _ -> rs.getObject("id", UUID::class.java) to rs.getBoolean("content_available") },
                artifactId,
                analysisRunId,
                sha256,
                sanitized.content,
                sanitized.schemaVersion,
                sizeBytes,
            ).firstOrNull()
            if (persisted == null || !persisted.second) {
                insertAssociation(analysisRunId, spanId, artifact.role, persisted?.first, CaptureFidelity.REMOVED.name, "ARTIFACT_REMOVED", artifact.schemaVersion, "application/json", sizeBytes)
                return@executeWithoutResult
            }
            insertAssociation(analysisRunId, spanId, artifact.role, persisted.first, sanitized.fidelity.name, sanitized.reason, artifact.schemaVersion, "application/json", sizeBytes)
        }
    }

    private fun insertAssociation(
        analysisRunId: UUID,
        spanId: UUID,
        role: String,
        artifactId: UUID?,
        fidelity: String,
        reason: String?,
        schemaVersion: String?,
        mediaType: String?,
        sizeBytes: Int?,
    ) {
        jdbc.update(
            """
            INSERT INTO analysis_run_execution_span_artifacts (
                analysis_run_id, span_id, artifact_id, role, fidelity, reason,
                schema_version, media_type, size_bytes
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (span_id, role) DO UPDATE
               SET artifact_id = EXCLUDED.artifact_id,
                   fidelity = EXCLUDED.fidelity,
                   reason = EXCLUDED.reason,
                   schema_version = EXCLUDED.schema_version,
                   media_type = EXCLUDED.media_type,
                   size_bytes = EXCLUDED.size_bytes
            """.trimIndent(),
            analysisRunId,
            spanId,
            artifactId,
            role,
            fidelity,
            reason,
            schemaVersion,
            mediaType,
            sizeBytes,
        )
    }

    fun summary(analysisRunId: UUID): AnalysisRunExecutionSummary? = jdbc.query(
        """
        SELECT capture_enabled, recording_state, completeness, started_at, finished_at
          FROM analysis_run_execution WHERE analysis_run_id = ?
        """.trimIndent(),
        { rs, _ ->
            val started = rs.getTimestamp("started_at").toInstant()
            val finished = rs.getTimestamp("finished_at")?.toInstant()
            AnalysisRunExecutionSummary(
                analysisRunId = analysisRunId,
                captureEnabled = rs.getBoolean("capture_enabled"),
                recordingState = rs.getString("recording_state"),
                completeness = rs.getString("completeness"),
                startedAt = started,
                finishedAt = finished,
                totalDurationMillis = Duration.between(started, finished ?: Instant.now()).toMillis().coerceAtLeast(0),
            )
        },
        analysisRunId,
    ).firstOrNull()

    internal fun page(analysisRunId: UUID, limit: Int, cursor: ExecutionSpanCursor?): ExecutionSpanPage {
        val cursorClause = if (cursor == null) "" else "AND (started_at, id) > (?, ?)"
        val parameters = mutableListOf<Any>(analysisRunId)
        if (cursor != null) {
            parameters += Timestamp.from(cursor.startedAt)
            parameters += cursor.id
        }
        parameters += limit + 1
        val rows = jdbc.query(
            """
            SELECT id, parent_span_id, operation_id, stage_id, kind, name,
                   started_at, ended_at, duration_millis, status, attempt,
                   provider_id, model_id, http_status, safe_error_code, attributes::text AS attributes
              FROM analysis_run_execution_spans
             WHERE analysis_run_id = ? $cursorClause
             ORDER BY started_at, id
             LIMIT ?
            """.trimIndent(),
            { rs, _ -> rs.toExecutionSpan() },
            *parameters.toTypedArray(),
        )
        val hasMore = rows.size > limit
        val selected = rows.take(limit)
        val selectedIds = selected.map(ExecutionSpanRow::id)
        val descriptors = descriptors(analysisRunId, selectedIds)
        val links = domainLinks(analysisRunId, selectedIds)
        val items = selected.map {
            it.toResponse(objectMapper.readTree(it.attributes), descriptors[it.id].orEmpty(), links[it.id].orEmpty())
        }
        val last = selected.lastOrNull()
        return ExecutionSpanPage(
            items = items,
            nextCursor = if (hasMore && last != null) ExecutionSpanCursorCodec.encode(last.startedAt, last.id) else null,
        )
    }

    fun span(analysisRunId: UUID, spanId: UUID): ExecutionSpanResponse? {
        val row = jdbc.query(
            """
            SELECT id, parent_span_id, operation_id, stage_id, kind, name,
                   started_at, ended_at, duration_millis, status, attempt,
                   provider_id, model_id, http_status, safe_error_code, attributes::text AS attributes
              FROM analysis_run_execution_spans
             WHERE analysis_run_id = ? AND id = ?
            """.trimIndent(),
            { rs, _ -> rs.toExecutionSpan() },
            analysisRunId,
            spanId,
        ).firstOrNull() ?: return null
        return row.toResponse(
            objectMapper.readTree(row.attributes),
            descriptors(analysisRunId, listOf(spanId))[spanId].orEmpty(),
            domainLinks(analysisRunId, listOf(spanId))[spanId].orEmpty(),
        )
    }

    fun artifact(
        analysisRunId: UUID,
        artifactId: UUID,
        spanId: UUID? = null,
        role: String? = null,
    ): ExecutionArtifactResponse? = jdbc.query(
        """
        SELECT link.span_id, link.role, link.fidelity, link.reason, link.media_type, link.size_bytes,
               link.schema_version, artifact.content, artifact.capture_version, artifact.sanitizer_version
          FROM analysis_run_execution_span_artifacts link
          JOIN analysis_run_execution_artifacts artifact
            ON artifact.analysis_run_id = link.analysis_run_id AND artifact.id = link.artifact_id
          JOIN analysis_run_execution_spans span
            ON span.analysis_run_id = link.analysis_run_id AND span.id = link.span_id
         WHERE link.analysis_run_id = ? AND link.artifact_id = ?
           AND (CAST(? AS UUID) IS NULL OR link.span_id = CAST(? AS UUID))
           AND (CAST(? AS VARCHAR) IS NULL OR link.role = CAST(? AS VARCHAR))
         ORDER BY span.started_at, span.id, link.role
         LIMIT 1
        """.trimIndent(),
        { rs, _ ->
            val fidelity = rs.getString("fidelity")
            val content = rs.getString("content")
            ExecutionArtifactResponse(
                id = artifactId,
                spanId = rs.getObject("span_id", UUID::class.java),
                role = rs.getString("role"),
                fidelity = if (content == null) CaptureFidelity.REMOVED.name else fidelity,
                reason = if (content == null) rs.getString("reason") ?: "ARTIFACT_REMOVED" else rs.getString("reason"),
                mediaType = rs.getString("media_type"),
                content = content,
                schemaVersion = rs.getString("schema_version"),
                captureVersion = rs.getString("capture_version"),
                sanitizerVersion = rs.getString("sanitizer_version"),
                sizeBytes = rs.getInt("size_bytes"),
            )
        },
        analysisRunId,
        artifactId,
        spanId,
        spanId,
        role,
        role,
    ).firstOrNull()

    fun stopCapture(analysisRunId: UUID): Boolean = jdbc.update(
        """
        UPDATE analysis_run_execution
           SET capture_enabled = FALSE,
               recording_state = 'STOPPED',
               gap_reason = CASE WHEN finished_at IS NULL THEN COALESCE(gap_reason, 'CAPTURE_STOPPED') ELSE gap_reason END
         WHERE analysis_run_id = ?
        """.trimIndent(),
        analysisRunId,
    ) == 1

    fun removeArtifact(analysisRunId: UUID, artifactId: UUID): Boolean = transactionTemplate.execute {
        val exists = jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM analysis_run_execution_artifacts WHERE analysis_run_id = ? AND id = ?)",
            Boolean::class.java,
            analysisRunId,
            artifactId,
        ) == true
        if (!exists) return@execute false
        jdbc.update(
            """
            UPDATE analysis_run_execution_artifacts
               SET content = NULL, removed_at = COALESCE(removed_at, now())
             WHERE analysis_run_id = ? AND id = ?
            """.trimIndent(),
            analysisRunId,
            artifactId,
        )
        jdbc.update(
            """
            UPDATE analysis_run_execution_span_artifacts
               SET fidelity = 'REMOVED', reason = 'ARTIFACT_REMOVED'
             WHERE analysis_run_id = ? AND artifact_id = ?
            """.trimIndent(),
            analysisRunId,
            artifactId,
        )
        true
    } ?: false

    fun finishRecording(analysisRunId: UUID, incomplete: Boolean = false) {
        jdbc.update(
            """
            UPDATE analysis_run_execution
               SET finished_at = COALESCE(finished_at, now()),
                   recording_state = 'STOPPED',
                   completeness = CASE
                       WHEN ? OR gap_reason IS NOT NULL OR completeness = 'INCOMPLETE' THEN 'INCOMPLETE'
                       ELSE 'COMPLETE'
                   END
             WHERE analysis_run_id = ?
            """.trimIndent(),
            incomplete,
            analysisRunId,
        )
        jdbc.update(
            """
            UPDATE analysis_run_execution_spans
               SET ended_at = now(), duration_millis = GREATEST(0, (extract(epoch FROM (now() - started_at)) * 1000)::bigint),
                   status = 'INTERRUPTED', safe_error_code = 'RUN_TERMINATED_WITH_OPERATION_ACTIVE'
             WHERE analysis_run_id = ? AND status = 'RUNNING'
            """.trimIndent(),
            analysisRunId,
        )
        val interrupted = jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM analysis_run_execution_spans WHERE analysis_run_id = ? AND status = 'INTERRUPTED')",
            Boolean::class.java,
            analysisRunId,
        ) == true
        if (interrupted && !incomplete) {
            jdbc.update(
                "UPDATE analysis_run_execution SET completeness = 'INCOMPLETE', gap_reason = COALESCE(gap_reason, 'INTERRUPTED_OPERATION') WHERE analysis_run_id = ?",
                analysisRunId,
            )
        }
    }

    private fun domainLinks(analysisRunId: UUID, spanIds: List<UUID>): Map<UUID, List<ExecutionDomainLink>> {
        if (spanIds.isEmpty()) return emptyMap()
        val placeholders = spanIds.joinToString(",") { "?" }
        return jdbc.query(
            """
            SELECT DISTINCT span.id AS span_id, run.document_id, entry.id AS bibliography_entry_id
              FROM analysis_run_execution_spans span
              JOIN analysis_runs run ON run.id = span.analysis_run_id
              JOIN source_documents document ON document.id = run.document_id
              LEFT JOIN outbox_events event
                ON event.analysis_run_id = span.analysis_run_id AND event.event_id = span.event_id
              LEFT JOIN bibliography_entries entry
                ON entry.analysis_run_id = span.analysis_run_id
               AND entry.id::text = event.payload -> 'payload' ->> 'bibliographyEntryId'
             WHERE span.analysis_run_id = ? AND span.id IN ($placeholders)
               AND NOT EXISTS (SELECT 1 FROM source_document_tombstones tombstone WHERE tombstone.document_id = run.document_id)
            """.trimIndent(),
            { rs, _ ->
                val spanId = rs.getObject("span_id", UUID::class.java)
                val documentId = rs.getObject("document_id", UUID::class.java)
                val links = mutableListOf(
                    ExecutionDomainLink("ANALYSIS_RUN", analysisRunId, "/api/v1/analysis-runs/$analysisRunId"),
                    ExecutionDomainLink("SOURCE_DOCUMENT", documentId, "/api/v1/analysis-runs/$analysisRunId/source-document"),
                )
                rs.getObject("bibliography_entry_id", UUID::class.java)?.let { entryId ->
                    links += ExecutionDomainLink("BIBLIOGRAPHY_ENTRY", entryId, "/api/v1/analysis-runs/$analysisRunId/report")
                }
                spanId to links
            },
            analysisRunId,
            *spanIds.toTypedArray(),
        ).associate { it.first to it.second }
    }

    private fun descriptors(analysisRunId: UUID, spanIds: List<UUID>): Map<UUID, List<ExecutionArtifactDescriptor>> {
        if (spanIds.isEmpty()) return emptyMap()
        val placeholders = spanIds.joinToString(",") { "?" }
        val params = listOf(analysisRunId) + spanIds
        return jdbc.query(
            """
            SELECT link.span_id, link.artifact_id, link.role, link.fidelity, link.reason,
                   link.media_type, link.size_bytes, (artifact.removed_at IS NOT NULL) AS artifact_removed
              FROM analysis_run_execution_span_artifacts link
              LEFT JOIN analysis_run_execution_artifacts artifact
                ON artifact.analysis_run_id = link.analysis_run_id AND artifact.id = link.artifact_id
             WHERE link.analysis_run_id = ? AND link.span_id IN ($placeholders)
             ORDER BY link.role
            """.trimIndent(),
            { rs, _ ->
                val removed = rs.getBoolean("artifact_removed")
                ExecutionArtifactDescriptor(
                    id = rs.getObject("artifact_id", UUID::class.java),
                    role = rs.getString("role"),
                    fidelity = if (removed) CaptureFidelity.REMOVED.name else rs.getString("fidelity"),
                    reason = if (removed) rs.getString("reason") ?: "ARTIFACT_REMOVED" else rs.getString("reason"),
                    mediaType = rs.getString("media_type"),
                    sizeBytes = rs.getObject("size_bytes") as? Int,
                ).let { rs.getObject("span_id", UUID::class.java) to it }
            },
            *params.toTypedArray(),
        ).groupBy({ it.first }, { it.second })
    }

    private data class ExecutionSpanRow(
        val id: UUID,
        val parentSpanId: UUID?,
        val operationId: UUID,
        val stageId: String,
        val kind: String,
        val name: String,
        val startedAt: Instant,
        val endedAt: Instant?,
        val durationMillis: Long?,
        val status: String,
        val attempt: Int,
        val providerId: String?,
        val modelId: String?,
        val httpStatus: Int?,
        val safeErrorCode: String?,
        val attributes: String,
    ) {
        fun toResponse(
            attributes: JsonNode,
            artifacts: List<ExecutionArtifactDescriptor>,
            domainLinks: List<ExecutionDomainLink>,
        ): ExecutionSpanResponse {
            val trustBoundary = when {
                kind in setOf("INTERNAL", "PERSISTENCE", "TRANSFORMATION") -> "INTERNAL"
                kind == "QUEUE" -> "LOCAL"
                providerId == null || providerId.lowercase() in LOCAL_PROVIDERS -> "LOCAL"
                else -> "EXTERNAL"
            }
            val route = attributes.path("httpRoute").takeIf(JsonNode::isTextual)?.asText()
            return ExecutionSpanResponse(
                id, parentSpanId, operationId, stageId, kind, name, startedAt, endedAt, durationMillis,
                status, attempt, providerId, modelId, httpStatus, safeErrorCode, attributes,
                trustBoundary, route, domainLinks, artifacts,
            )
        }
    }

    companion object {
        private val LOCAL_PROVIDERS = setOf("mock", "ollama", "grobid", "local", "recorded-fixtures", "feature-hash")
    }

    private fun ResultSet.toExecutionSpan() = ExecutionSpanRow(
        id = getObject("id", UUID::class.java),
        parentSpanId = getObject("parent_span_id", UUID::class.java),
        operationId = getObject("operation_id", UUID::class.java),
        stageId = getString("stage_id"),
        kind = getString("kind"),
        name = getString("name"),
        startedAt = getTimestamp("started_at").toInstant(),
        endedAt = getTimestamp("ended_at")?.toInstant(),
        durationMillis = getObject("duration_millis")?.let { (it as Number).toLong() },
        status = getString("status"),
        attempt = getInt("attempt"),
        providerId = getString("provider_id"),
        modelId = getString("model_id"),
        httpStatus = getObject("http_status")?.let { (it as Number).toInt() },
        safeErrorCode = getString("safe_error_code"),
        attributes = getString("attributes"),
    )
}
