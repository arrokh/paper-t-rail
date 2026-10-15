package com.papertrail.api.infrastructure.providers

import java.time.Instant

interface ProviderEgressPermitGate {
    fun acquire(providerId: String, deadlineAt: Instant): AcquireResult

    sealed interface AcquireResult {
        data class Granted(val permit: Permit) : AcquireResult
        data object DeadlineExceeded : AcquireResult
        data object Unavailable : AcquireResult
    }

    interface Permit {
        fun release(): ReleaseResult
    }

    enum class ReleaseResult {
        RELEASED,
        NOT_OWNER,
        UNAVAILABLE,
    }
}
