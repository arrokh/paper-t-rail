package com.papertrail.api.analysis.execution.http

import io.swagger.v3.oas.annotations.media.Schema
import java.util.UUID

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
