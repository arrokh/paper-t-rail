package com.papertrail.api.analysis.execution

import com.fasterxml.jackson.databind.JsonNode
import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant
import java.util.UUID

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
    @field:Schema(description = "Allowlisted content-free attributes only; never includes payloads, hosts, query strings, or headers.")
    val attributes: JsonNode,
    @field:Schema(allowableValues = ["INTERNAL", "LOCAL", "EXTERNAL"])
    val trustBoundary: String,
    @field:Schema(description = "Sanitized route template/path without host, query, or raw URL.")
    val httpRoute: String?,
    val domainLinks: List<ExecutionDomainLink>,
    val artifactRoles: List<ExecutionArtifactDescriptor>,
)
