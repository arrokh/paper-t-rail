package com.papertrail.api.analysis.recovery.domain

enum class RecoveryWorkEventType(
    val code: String,
    val requiresAttemptNumber: Boolean,
) {
    ATTEMPT_STARTED("ATTEMPT_STARTED", true),
    ATTEMPT_COMPLETED("ATTEMPT_COMPLETED", true),
    ATTEMPT_FAILED("ATTEMPT_FAILED", true),
    WORK_SKIPPED("WORK_SKIPPED", false),
}
