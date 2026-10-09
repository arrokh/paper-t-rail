package com.papertrail.api.analysis.recovery.http

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.AssertTrue

@Schema(description = "Explicit human confirmation that the exact staged PDF is the cited work or intended alternate version.")
data class ConfirmRecoveryIdentityRequest(
    @field:AssertTrue(message = "Explicitly confirm the exact PDF version before continuing.")
    val confirmExactVersion: Boolean,
)
