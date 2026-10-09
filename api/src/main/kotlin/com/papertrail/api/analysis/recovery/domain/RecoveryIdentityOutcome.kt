package com.papertrail.api.analysis.recovery.domain

enum class RecoveryIdentityOutcome {
    VALIDATED,
    NEEDS_CONFIRMATION,
    MISMATCH,
}
