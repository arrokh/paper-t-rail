package com.papertrail.api.scholarly.acquisition.service

import com.papertrail.api.scholarly.acquisition.domain.CitedPaperAccessCause

data class CitedPaperIdentityValidationResult(
    val status: CitedPaperIdentityValidationStatus,
    val reasonCode: String,
    val failureCause: CitedPaperAccessCause? = null,
) {
    init {
        require(reasonCode.isNotBlank()) { "Cited Paper identity validation requires a reason code." }
        require(status == CitedPaperIdentityValidationStatus.FAILED || failureCause == null) {
            "Only failed Cited Paper identity validation may have a failure cause."
        }
    }

    fun accessCause(): CitedPaperAccessCause? = when (status) {
        CitedPaperIdentityValidationStatus.VALIDATED -> null
        CitedPaperIdentityValidationStatus.NEEDS_CONFIRMATION -> CitedPaperAccessCause.FULL_TEXT_IDENTITY_UNVERIFIED
        CitedPaperIdentityValidationStatus.MISMATCH -> CitedPaperAccessCause.FULL_TEXT_IDENTITY_MISMATCH
        CitedPaperIdentityValidationStatus.FAILED -> failureCause ?: CitedPaperAccessCause.FULL_TEXT_IDENTITY_VALIDATION_FAILED
    }
}
