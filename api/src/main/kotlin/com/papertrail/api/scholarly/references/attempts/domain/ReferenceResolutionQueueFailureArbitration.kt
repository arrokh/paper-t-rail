package com.papertrail.api.scholarly.references.attempts.domain

import java.time.Instant

sealed interface ReferenceResolutionQueueFailureArbitration {
    data object FailureRecorded : ReferenceResolutionQueueFailureArbitration
    data object ResolutionAlreadySaved : ReferenceResolutionQueueFailureArbitration
    data class Defer(val until: Instant) : ReferenceResolutionQueueFailureArbitration
}
