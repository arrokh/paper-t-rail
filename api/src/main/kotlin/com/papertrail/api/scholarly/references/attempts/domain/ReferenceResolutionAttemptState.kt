package com.papertrail.api.scholarly.references.attempts.domain

enum class ReferenceResolutionAttemptState {
    RESERVED,
    IN_FLIGHT,
    COMPLETED,
    UNKNOWN,
}
