package com.papertrail.api.analysis.execution

import com.fasterxml.jackson.databind.JsonNode
import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant
import java.util.UUID

@Schema(description = "Run-level availability, capture choice, and elapsed interval for inspectable execution.")
data class AnalysisRunExecutionSummary(
    val analysisRunId: UUID,
    @field:Schema(description = "Whether future sanitized artifacts may be captured.")
    val captureEnabled: Boolean,
    @field:Schema(allowableValues = ["RECORDING", "STOPPED", "NOT_RECORDED"], description = "Explicitly distinguishes legacy history from stopped capture.")
    val recordingState: String,
    @field:Schema(allowableValues = ["COMPLETE", "INCOMPLETE", "RECORDING", "NOT_RECORDED"])
    val completeness: String,
    val startedAt: Instant?,
    val finishedAt: Instant?,
    @field:Schema(description = "Elapsed time from recording start to finish or now; overlapping spans are not summed.")
    val totalDurationMillis: Long?,
)

@Schema(description = "One cursor page of execution spans, ordered by start time then span ID ascending.")
data class ExecutionSpanPage(
    val items: List<ExecutionSpanResponse>,
    @field:Schema(description = "Opaque forward cursor, or null when there are no later spans.")
    val nextCursor: String?,
)

@Schema(description = "Content-free execution timing and safe metadata. Artifact content is fetched separately.")
data class ExecutionSpanResponse(
    val id: UUID,
    val parentSpanId: UUID?,
    val operationId: UUID,
    @field:Schema(allowableValues = ["source", "references", "access", "evidence", "verification"])
    val stageId: String,
    @field:Schema(allowableValues = ["INTERNAL", "PROVIDER", "QUEUE", "PERSISTENCE", "TRANSFORMATION"])
    val kind: String,
    val name: String,
    val startedAt: Instant,
    val endedAt: Instant?,
    val durationMillis: Long?,
    @field:Schema(allowableValues = ["RUNNING", "SUCCEEDED", "FAILED", "SKIPPED", "REUSED", "INTERRUPTED"])
    val status: String,
    @field:Schema(minimum = "1")
    val attempt: Int,
    val providerId: String?,
    val modelId: String?,
    val httpStatus: Int?,
    val safeErrorCode: String?,
    val attributes: JsonNode,
    val artifactRoles: List<ExecutionArtifactDescriptor>,
)

@Schema(description = "Descriptor for a sanitized execution artifact; payloads are loaded only when explicitly requested.")
data class ExecutionArtifactDescriptor(
    val id: UUID?,
    @field:Schema(allowableValues = ["INPUT", "REQUEST", "RESPONSE", "RESULT"])
    val role: String,
    @field:Schema(allowableValues = ["COMPLETE", "SANITIZED", "PARTIAL", "OMITTED", "REMOVED", "UNAVAILABLE"])
    val fidelity: String,
    val reason: String?,
    val mediaType: String?,
    val sizeBytes: Int?,
)

@Schema(description = "One sanitized execution artifact. Removed or omitted artifacts have no content.")
data class ExecutionArtifactResponse(
    val id: UUID,
    val spanId: UUID,
    @field:Schema(allowableValues = ["INPUT", "REQUEST", "RESPONSE", "RESULT"])
    val role: String,
    @field:Schema(allowableValues = ["COMPLETE", "SANITIZED", "PARTIAL", "OMITTED", "REMOVED", "UNAVAILABLE"])
    val fidelity: String,
    val reason: String?,
    val mediaType: String?,
    val content: String?,
    val schemaVersion: String?,
    val captureVersion: String?,
    val sanitizerVersion: String?,
    val sizeBytes: Int?,
)

data class ExecutionSpanHandle(
    val id: UUID,
    val analysisRunId: UUID,
    val operationId: UUID,
    val startedAt: Instant,
    val startedNanos: Long,
    val stageId: String = "source",
    val attempt: Int = 1,
    val eventId: UUID? = null,
)

data class ExecutionSpanSpec(
    val stageId: String,
    val kind: String,
    val name: String,
    val attempt: Int = 1,
    val eventId: UUID? = null,
    val parentSpanId: UUID? = null,
    val providerId: String? = null,
    val modelId: String? = null,
    val attributes: Map<String, Any?> = emptyMap(),
    val operationId: UUID? = null,
)

data class ExecutionSpanArtifactSpec(
    val role: String,
    val schemaVersion: String,
    val fields: Map<String, Any?>,
)
