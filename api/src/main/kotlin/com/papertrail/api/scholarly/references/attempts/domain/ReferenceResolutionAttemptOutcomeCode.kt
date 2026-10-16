package com.papertrail.api.scholarly.references.attempts.domain

enum class ReferenceResolutionAttemptOutcomeCode {
    FOUND,
    NOT_FOUND,
    UNUSABLE,
    RETRYABLE_FAILURE,
    PERMANENT_FAILURE,
    UNKNOWN,
    NOT_SENT,
}
