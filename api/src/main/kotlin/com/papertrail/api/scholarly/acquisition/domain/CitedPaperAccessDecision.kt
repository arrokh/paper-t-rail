package com.papertrail.api.scholarly.acquisition.domain

data class CitedPaperAccessDecision(
    val accessStatus: CitedPaperAccessStatus,
    val verificationScope: VerificationScope,
    val finalVerificationStatus: TerminalVerificationStatus?,
    val terminalReason: String?,
)
