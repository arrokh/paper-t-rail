package com.papertrail.api.scholarly.references.attempts.domain

enum class ReferenceResolutionProviderLookupOutcomeCode {
    FOUND,
    NOT_FOUND,
    UNUSABLE,
    RETRYABLE_FAILURE,
    PERMANENT_FAILURE,
}
