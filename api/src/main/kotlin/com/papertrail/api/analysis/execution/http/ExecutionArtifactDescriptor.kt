package com.papertrail.api.analysis.execution.http

import io.swagger.v3.oas.annotations.media.Schema
import java.util.UUID

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
