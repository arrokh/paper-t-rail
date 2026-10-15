package com.papertrail.api.scholarly.references.attempts.domain

enum class ReferenceResolutionAttemptNoSendReason {
    EXISTING_RESERVATION,
    EXISTING_ATTEMPT,
    UNKNOWN_OUTCOME,
    MISSING_ENTRY,
    FINAL_RESOLUTION,
    DEADLINE_EXHAUSTED,
    RETRY_NOT_DUE,
    BUDGET_EXHAUSTED,
    LOOKUP_ORDER_NOT_READY,
    LOOKUP_ALREADY_RECORDED,
    OWNERSHIP_LOST,
    LEASE_EXPIRED,
}
