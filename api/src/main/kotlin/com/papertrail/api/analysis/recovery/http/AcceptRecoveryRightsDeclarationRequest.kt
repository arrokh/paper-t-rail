package com.papertrail.api.analysis.recovery.http

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank

@Schema(description = "Explicit acceptance of the current versioned PDF-rights declaration before an upload intent is created.")
data class AcceptRecoveryRightsDeclarationRequest(
    @field:NotBlank
    @field:Schema(description = "Exact current version returned by GET /api/v1/recovery-rights-declaration.")
    val rightsDeclarationVersion: String,
    @field:Schema(description = "Must be true to record the researcher's PDF-rights declaration.")
    val accepted: Boolean,
)
