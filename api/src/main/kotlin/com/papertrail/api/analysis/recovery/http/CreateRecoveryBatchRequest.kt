package com.papertrail.api.analysis.recovery.http

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotNull
import java.util.UUID

data class CreateRecoveryBatchRequest(
    @field:NotNull
    @field:Schema(description = "Client-generated key used to make batch creation idempotent.", format = "uuid")
    val idempotencyKey: UUID,
    @field:Schema(description = "The exact rights declaration version shown before acceptance.")
    val rightsDeclarationVersion: String,
    @field:Schema(description = "Must be true after the user accepts the versioned rights declaration.")
    val rightsDeclarationAccepted: Boolean,
)
