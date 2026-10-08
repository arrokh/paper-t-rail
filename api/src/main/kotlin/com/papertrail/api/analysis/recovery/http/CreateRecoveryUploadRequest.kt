package com.papertrail.api.analysis.recovery.http

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.util.UUID

data class CreateRecoveryUploadRequest(
    @field:Schema(description = "Client-generated key used to make upload-intent creation idempotent.", format = "uuid")
    val idempotencyKey: UUID,
    @field:NotBlank
    @field:Size(max = 255)
    @field:Schema(description = "The selected file name. It is sanitized before persistence and is never used as an object key.")
    val filename: String,
    @field:Min(1)
    @field:Schema(description = "Expected upload size in bytes; the signed request binds the exact length.", minimum = "1")
    val expectedSize: Long,
    @field:Pattern(regexp = "^[0-9a-f]{64}$")
    @field:Schema(description = "Lowercase SHA-256 of the selected file bytes.", pattern = "^[0-9a-f]{64}$")
    val expectedSha256: String,
)
