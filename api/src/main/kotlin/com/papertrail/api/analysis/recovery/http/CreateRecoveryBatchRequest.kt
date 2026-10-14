package com.papertrail.api.analysis.recovery.http

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotNull
import java.util.UUID

data class CreateRecoveryBatchRequest(
    @field:NotNull
    @field:Schema(description = "Client-generated key used to make batch creation idempotent.", format = "uuid")
    val idempotencyKey: UUID,
    @field:Schema(description = "Optional exact rights declaration version. Supply together with rightsDeclarationAccepted to accept PDF rights at creation; omit both for DOI-only assistance.", nullable = true)
    val rightsDeclarationVersion: String? = null,
    @field:Schema(description = "Must be true when accepting the versioned PDF-rights declaration at batch creation; omit with rightsDeclarationVersion for DOI-only work.", nullable = true)
    val rightsDeclarationAccepted: Boolean? = null,
)
