package com.papertrail.api.analysis.successor.http

import com.papertrail.api.analysis.http.RunConfigurationRequest
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.Valid
import jakarta.validation.constraints.Min

@Schema(description = "Read-only preview of a successor Analysis Run against the Recovery Batch's current server-recorded selections.")
data class SuccessorRunPreviewRequest(
    @field:Min(0)
    @field:Schema(description = "Batch revision returned by the current Recovery Batch GET.", minimum = "0")
    val expectedRevision: Long,
    @field:Valid
    @field:Schema(description = "Complete successor run configuration. External providers require explicit matching consent.")
    val configuration: RunConfigurationRequest = RunConfigurationRequest(),
)
