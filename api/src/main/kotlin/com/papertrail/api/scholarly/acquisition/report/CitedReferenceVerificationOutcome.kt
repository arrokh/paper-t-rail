package com.papertrail.api.scholarly.acquisition.report

import java.util.UUID

data class CitedReferenceVerificationOutcome(
    val atomicClaimId: UUID,
    val finalStatus: String?,
    val verificationScope: String,
    val terminalReason: String?,
)
