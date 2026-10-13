package com.papertrail.api.analysis.successor.http

import com.papertrail.api.analysis.http.RunConfigurationRequest
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotNull
import java.util.UUID

@Schema(description = "Idempotent submission of the Recovery Batch's current server-recorded selections as an immutable successor Analysis Run.")
data class SuccessorRunSubmissionRequest(
    @field:NotNull
    @field:Schema(description = "Client-generated key for retrying this exact submission.", format = "uuid", required = true)
    val idempotencyKey: UUID,
    @field:Min(0)
    @field:Schema(description = "Batch revision returned by the current Recovery Batch GET or preview.", minimum = "0", required = true)
    val expectedRevision: Long,
    @field:Valid
    @field:Schema(description = "Complete successor run configuration. External providers require explicit matching consent.")
    val configuration: RunConfigurationRequest = RunConfigurationRequest(),
)
