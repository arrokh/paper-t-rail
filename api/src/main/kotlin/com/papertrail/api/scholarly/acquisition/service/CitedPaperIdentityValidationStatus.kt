package com.papertrail.api.scholarly.acquisition.service

enum class CitedPaperIdentityValidationStatus {
    VALIDATED,
    NEEDS_CONFIRMATION,
    MISMATCH,
    FAILED,
}
