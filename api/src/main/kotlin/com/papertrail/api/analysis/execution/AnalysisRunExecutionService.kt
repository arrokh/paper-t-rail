package com.papertrail.api.analysis.execution

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.net.URI
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

@Service
class AnalysisRunExecutionService(
    private val repository: AnalysisRunExecutionRepository,
    private val sanitizer: ExecutionCaptureSanitizer,
    private val objectMapper: ObjectMapper,
) {
    private val activeSpan = ThreadLocal<ExecutionSpanHandle?>()
    private val captureNanosBySpan = ThreadLocal.withInitial { mutableMapOf<UUID, Long>() }

    fun <T> record(
        analysisRunId: UUID,
        spec: ExecutionSpanSpec,
        operation: () -> T,
    ): T = recordWithStatus(analysisRunId, spec, operation) { "SUCCEEDED" }

    fun <T> recordWithStatus(
        analysisRunId: UUID,
        spec: ExecutionSpanSpec,
        operation: () -> T,
        successStatus: (T) -> String,
    ): T {
        val previousSpan = activeSpan.get()
        val handle = startSpan(analysisRunId, spec)
        activeSpan.set(handle)
        try {
            val result = operation()
            finishSpan(handle, successStatus(result), null)
            finishIfTerminal(analysisRunId)
            return result
        } catch (exception: Exception) {
            finishSpan(handle, "FAILED", "OPERATION_FAILED")
            finishIfTerminal(analysisRunId)
            throw exception
        } finally {
            if (previousSpan == null) activeSpan.remove() else activeSpan.set(previousSpan)
        }
    }

    fun <T> recordCurrentProviderCall(
        operationKey: String,
        name: String,
        providerId: String,
        modelId: String?,
        attributes: Map<String, Any?> = emptyMap(),
        analysisRunId: UUID? = null,
        stageId: String? = null,
        operation: () -> T,
    ): T {
        val parent = activeSpan.get()?.takeIf { analysisRunId == null || it.analysisRunId == analysisRunId }
        val effectiveRunId = parent?.analysisRunId ?: analysisRunId ?: return operation()
        val effectiveStageId = parent?.stageId ?: stageId ?: return operation()
        if (!SAFE_OPERATION_KEY.matches(operationKey)) {
            markGap(effectiveRunId, "UNSAFE_SPAN_METADATA_OMITTED")
            return operation()
        }
        val operationId = parent?.eventId?.let { ExecutionOperationId.forEvent(it, "provider-call:$operationKey") }
            ?: parent?.operationId?.let { UUID.nameUUIDFromBytes("$it:provider-call:$operationKey".toByteArray(Charsets.UTF_8)) }
            ?: UUID.nameUUIDFromBytes("$effectiveRunId:provider-call:$operationKey".toByteArray(Charsets.UTF_8))
        return record(
            effectiveRunId,
            ExecutionSpanSpec(
                stageId = effectiveStageId,
                kind = "PROVIDER",
                name = name,
                attempt = parent?.attempt ?: 1,
                eventId = parent?.eventId,
                parentSpanId = parent?.id,
                providerId = providerId,
                modelId = modelId,
                operationId = operationId,
                attributes = attributes,
            ),
            operation,
        )
    }

    fun startSpan(analysisRunId: UUID, spec: ExecutionSpanSpec): ExecutionSpanHandle? {
        if (!validSpec(spec)) {
            markGap(analysisRunId, "UNSAFE_SPAN_METADATA_OMITTED")
            return null
        }
        val attributes = safeAttributes(spec.attributes, analysisRunId)
        val activeParent = activeSpan.get()?.takeIf { it.analysisRunId == analysisRunId }?.id
        val causalParent = if (activeParent == null && spec.parentSpanId == null) {
            spec.causationEventId?.let { repository.causalParentSpanId(analysisRunId, it) }
        } else null
        val linkedSpec = if (spec.parentSpanId == null) spec.copy(parentSpanId = activeParent ?: causalParent) else spec
        return try {
            repository.startSpan(analysisRunId, linkedSpec, attributes)
        } catch (_: Exception) {
            markGap(analysisRunId, "SPAN_STORAGE_UNAVAILABLE")
            logStorageFailure(analysisRunId)
            null
        }
    }

    fun finishSpan(handle: ExecutionSpanHandle?, status: String, errorCode: String? = null, httpStatus: Int? = null) {
        if (handle == null) return
        val captureNanos = captureNanosBySpan.get().remove(handle.id)
        if (captureNanosBySpan.get().isEmpty()) captureNanosBySpan.remove()
        if (status !in SPAN_STATUSES || errorCode?.matches(SAFE_ERROR_CODE) == false || httpStatus?.let { it !in 100..599 } == true) {
            markGap(handle.analysisRunId, "UNSAFE_SPAN_RESULT_OMITTED")
            return
        }
        val captureOverheadMillis = captureNanos?.div(1_000_000L)
        safely(handle.analysisRunId) {
            repository.finishSpan(handle, status, errorCode, httpStatus, captureOverheadMillis)
        }
    }

    fun recordInterval(
        analysisRunId: UUID,
        spec: ExecutionSpanSpec,
        startedAt: Instant,
        endedAt: Instant,
    ) {
        if (!validSpec(spec) || endedAt.isBefore(startedAt)) {
            markGap(analysisRunId, "INTERVAL_TIMESTAMPS_UNAVAILABLE")
            return
        }
        val attributes = safeAttributes(spec.attributes, analysisRunId)
        try {
            repository.recordInterval(analysisRunId, spec, attributes, startedAt, endedAt)
        } catch (_: Exception) {
            markGap(analysisRunId, "SPAN_STORAGE_UNAVAILABLE")
            logStorageFailure(analysisRunId)
        }
    }

    fun recordQueueIntervals(
        analysisRunId: UUID,
        eventId: UUID,
        attempt: Int,
        stageId: String,
        queueWaitStartedAt: Instant?,
        retryScheduledAt: Instant?,
        retryDueAt: Instant?,
        causationEventId: UUID?,
    ) {
        val observedAt = Instant.now()
        if (retryScheduledAt != null && retryDueAt != null) {
            recordInterval(
                analysisRunId,
                ExecutionSpanSpec(stageId, "QUEUE", "Retry backoff", attempt + 1, eventId, operationId = eventId, causationEventId = causationEventId),
                retryScheduledAt,
                retryDueAt,
            )
            recordInterval(
                analysisRunId,
                ExecutionSpanSpec(stageId, "QUEUE", "Queue wait", attempt + 1, eventId, operationId = eventId, causationEventId = causationEventId),
                retryDueAt,
                observedAt,
            )
        } else if (retryScheduledAt != null || retryDueAt != null) {
            markGap(analysisRunId, "RETRY_SCHEDULE_TIMESTAMPS_UNAVAILABLE")
        } else if (queueWaitStartedAt != null) {
            recordInterval(
                analysisRunId,
                ExecutionSpanSpec(stageId, "QUEUE", "Queue wait", attempt + 1, eventId, operationId = eventId, causationEventId = causationEventId),
                queueWaitStartedAt,
                observedAt,
            )
        } else if (attempt > 0) {
            markGap(analysisRunId, "RETRY_SCHEDULE_TIMESTAMPS_UNAVAILABLE")
        } else {
            markGap(analysisRunId, "QUEUE_ENQUEUE_TIMESTAMP_UNAVAILABLE")
        }
    }

    fun capture(analysisRunId: UUID, spanId: UUID, artifact: ExecutionSpanArtifactSpec) {
        if (artifact.role !in ARTIFACT_ROLES) {
            markGap(analysisRunId, "UNSAFE_ARTIFACT_METADATA_OMITTED")
            return
        }
        measureCapture(spanId) {
            try {
                persistSanitized(analysisRunId, spanId, artifact, sanitizer.sanitize(artifact.schemaVersion, artifact.fields))
            } catch (_: Exception) {
                markGap(analysisRunId, "ARTIFACT_STORAGE_UNAVAILABLE")
                logStorageFailure(analysisRunId)
            }
        }
    }

    fun captureCurrent(artifact: ExecutionSpanArtifactSpec) {
        val handle = activeSpan.get() ?: return
        capture(handle.analysisRunId, handle.id, artifact)
    }

    fun captureCurrentOpenAiRequest(body: ByteArray) = captureCurrentBody("REQUEST") { sanitizer.sanitizeOpenAiRequestBody(body) }

    fun captureCurrentOpenAiResponse(body: ByteArray) = captureCurrentBody("RESPONSE") { sanitizer.sanitizeOpenAiResponseBody(body) }

    fun captureCurrentSystemOneRequest(body: ByteArray) = captureCurrentBody("REQUEST") { sanitizer.sanitizeSystemOneRequestBody(body) }

    fun captureCurrentJevResponse(body: ByteArray) = captureCurrentBody("RESPONSE") { sanitizer.sanitizeJevResponseBody(body) }

    fun captureCurrentSystemOneResponse(body: ByteArray, schemaVersion: String = "system-one-response-v1") =
        captureCurrentBody("RESPONSE") { sanitizer.sanitizeProviderJsonBody(schemaVersion, body) }

    fun captureCurrentCrossrefRequest(uri: URI) =
        captureCurrentBody("REQUEST") { sanitizer.sanitizeCrossrefRequest(uri) }

    fun captureCurrentCrossrefResponse(body: ByteArray) =
        captureCurrentBody("RESPONSE") { sanitizer.sanitizeProviderJsonBody("crossref-response-v1", body) }

    fun captureCurrentUnpaywallRequest(uri: URI) =
        captureCurrentBody("REQUEST") { sanitizer.sanitizeUnpaywallRequest(uri) }

    fun captureCurrentUnpaywallResponse(body: ByteArray) =
        captureCurrentBody("RESPONSE") { sanitizer.sanitizeProviderJsonBody("unpaywall-response-v1", body) }

    fun captureCurrentOpenAccessRequest(uri: URI) =
        captureCurrentBody("REQUEST") { sanitizer.sanitizeOpenAccessRequest(uri) }

    fun omitCurrentBody(role: String, schemaVersion: String, reason: String) {
        val handle = activeSpan.get() ?: return
        if (role !in ARTIFACT_ROLES) {
            markGap(handle.analysisRunId, "UNSAFE_ARTIFACT_METADATA_OMITTED")
            return
        }
        captureCurrentBody(role) { sanitizer.omittedBody(schemaVersion, reason) }
    }

    private fun captureCurrentBody(role: String, sanitized: () -> SanitizedExecutionArtifact) {
        val handle = activeSpan.get() ?: return
        captureBody(handle.analysisRunId, handle.id, role, sanitized)
    }

    private fun captureBody(
        analysisRunId: UUID,
        spanId: UUID,
        role: String,
        sanitized: () -> SanitizedExecutionArtifact,
    ) {
        if (role !in ARTIFACT_ROLES) {
            markGap(analysisRunId, "UNSAFE_ARTIFACT_METADATA_OMITTED")
            return
        }
        measureCapture(spanId) {
            try {
                val captured = sanitized()
                val artifact = ExecutionSpanArtifactSpec(role, captured.schemaVersion, emptyMap())
                persistSanitized(analysisRunId, spanId, artifact, captured)
            } catch (_: Exception) {
                markGap(analysisRunId, "ARTIFACT_STORAGE_UNAVAILABLE")
                logStorageFailure(analysisRunId)
            }
        }
    }

    private inline fun measureCapture(spanId: UUID, operation: () -> Unit) {
        val startedNanos = System.nanoTime()
        try {
            operation()
        } finally {
            val elapsedNanos = (System.nanoTime() - startedNanos).coerceAtLeast(0L)
            val captures = captureNanosBySpan.get()
            val previousNanos = captures[spanId] ?: 0L
            captures[spanId] = if (Long.MAX_VALUE - previousNanos < elapsedNanos) Long.MAX_VALUE else previousNanos + elapsedNanos
        }
    }

    private fun persistSanitized(
        analysisRunId: UUID,
        spanId: UUID,
        artifact: ExecutionSpanArtifactSpec,
        sanitized: SanitizedExecutionArtifact,
    ) {
        repository.recordArtifact(analysisRunId, spanId, artifact, sanitized, sanitized.content?.let(::sha256Hex))
    }

    fun summary(analysisRunId: UUID): AnalysisRunExecutionSummary {
        requireRun(analysisRunId)
        return repository.summary(analysisRunId) ?: AnalysisRunExecutionSummary(
            analysisRunId = analysisRunId,
            captureRequested = null,
            captureEnabled = false,
            recordingState = "NOT_RECORDED",
            completeness = "NOT_RECORDED",
            startedAt = null,
            finishedAt = null,
            totalDurationMillis = null,
        )
    }

    fun spans(analysisRunId: UUID, limit: Int, cursor: String?): ExecutionSpanPage {
        requireRun(analysisRunId)
        val boundedLimit = limit.coerceIn(1, MAX_PAGE_SIZE)
        val decoded = cursor?.let(ExecutionSpanCursorCodec::decode)
        return repository.page(analysisRunId, boundedLimit, decoded)
    }

    fun span(analysisRunId: UUID, spanId: UUID): ExecutionSpanResponse {
        requireRun(analysisRunId)
        return repository.span(analysisRunId, spanId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Execution span not found.")
    }

    fun artifact(analysisRunId: UUID, artifactId: UUID, spanId: UUID? = null, role: String? = null): ExecutionArtifactResponse {
        requireRun(analysisRunId)
        if (role != null && role !in ARTIFACT_ROLES) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Artifact role must be INPUT, REQUEST, RESPONSE, or RESULT.")
        }
        return repository.artifact(analysisRunId, artifactId, spanId, role)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Execution artifact association not found in this Analysis Run.")
    }

    fun stopCapture(analysisRunId: UUID): AnalysisRunExecutionSummary {
        requireRun(analysisRunId)
        if (!repository.stopCapture(analysisRunId)) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "Execution recording is unavailable for this legacy Analysis Run.")
        }
        return summary(analysisRunId)
    }

    fun removeArtifact(analysisRunId: UUID, artifactId: UUID) {
        requireRun(analysisRunId)
        if (!repository.removeArtifact(analysisRunId, artifactId)) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Execution artifact not found.")
        }
    }

    fun finishIfTerminal(analysisRunId: UUID) = safely(analysisRunId) {
        val status = repository.terminalStatus(analysisRunId) ?: return@safely
        repository.finishRecording(analysisRunId, incomplete = status == "FAILED")
    }

    private fun requireRun(analysisRunId: UUID) {
        if (!repository.runExists(analysisRunId)) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Analysis Run not found.")
        }
    }

    private fun validSpec(spec: ExecutionSpanSpec): Boolean =
        spec.stageId in STAGES && spec.kind in KINDS && spec.name.matches(SAFE_LABEL) && spec.attempt > 0 &&
            (spec.providerId == null || sanitizer.isSafeIdentifier(spec.providerId)) &&
            (spec.modelId == null || sanitizer.isSafeIdentifier(spec.modelId))

    private fun safeAttributes(attributes: Map<String, Any?>, analysisRunId: UUID): String {
        val safe = attributes.filter { (key, value) ->
            key in SAFE_ATTRIBUTE_KEYS && when (value) {
                is String -> when (key) {
                    "httpRoute" -> sanitizer.isSafeHttpRoute(value)
                    "sourceHash" -> value.matches(SAFE_HASH)
                    else -> sanitizer.isSafeIdentifier(value)
                }
                is Number -> value.toLong() >= 0
                is Boolean -> true
                else -> false
            }
        }
        if (safe.size != attributes.size) markGap(analysisRunId, "UNSAFE_SPAN_ATTRIBUTES_OMITTED")
        return objectMapper.writeValueAsString(safe)
    }

    private fun markGap(analysisRunId: UUID, reason: String) {
        safely(analysisRunId) { repository.markGap(analysisRunId, reason) }
    }

    private fun safely(analysisRunId: UUID, operation: () -> Unit) {
        try {
            operation()
        } catch (_: Exception) {
            logStorageFailure(analysisRunId)
        }
    }

    private fun logStorageFailure(analysisRunId: UUID) {
        logger.atWarn()
            .addKeyValue("analysisRunId", analysisRunId)
            .log("Analysis Run execution recording is incomplete because persistence failed")
    }

    private fun sha256Hex(content: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(content.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { byte -> "%02x".format(byte) }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(AnalysisRunExecutionService::class.java)
        private val SAFE_LABEL = Regex("^[A-Za-z0-9][A-Za-z0-9 ._/-]{0,119}$")
        private val SAFE_OPERATION_KEY = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,99}$")
        private val SAFE_HASH = Regex("^[0-9a-f]{64}$")
        private val SAFE_ERROR_CODE = Regex("^[A-Z][A-Z0-9_]{0,119}$")
        private val STAGES = setOf("source", "references", "access", "evidence", "verification")
        private val KINDS = setOf("INTERNAL", "PROVIDER", "QUEUE", "PERSISTENCE", "TRANSFORMATION")
        private val SPAN_STATUSES = setOf("SUCCEEDED", "FAILED", "SKIPPED", "REUSED", "INTERRUPTED")
        private val ARTIFACT_ROLES = setOf("INPUT", "REQUEST", "RESPONSE", "RESULT")
        private val SAFE_ATTRIBUTE_KEYS = setOf(
            "providerId", "modelId", "parserId", "parserVersion", "sourceHash", "httpMethod", "httpStatus",
            "queueName", "eventType", "eventAttempt", "byteCount", "itemCount", "durationMillis",
            "firstChunkMillis", "reasonCode", "external", "httpRoute",
        )
        const val MAX_PAGE_SIZE = 100
    }
}
