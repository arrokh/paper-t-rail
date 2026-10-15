package com.papertrail.api.scholarly.references.client

import com.papertrail.api.scholarly.references.attempts.domain.ReferenceResolutionAttemptOutcomeCode
import java.time.Instant

internal data class ProviderHttpFailureClassification(
    val outcomeCode: ReferenceResolutionAttemptOutcomeCode,
    val retryAfterMillis: Long?,
)

internal object ProviderHttpFailurePolicy {
    fun classify(
        status: Int,
        retryAfterValues: List<String>?,
        now: Instant,
    ): ProviderHttpFailureClassification {
        require(status in 100..599) { "HTTP status must be a valid numeric status." }
        val retryable = status == REQUEST_TIMEOUT || status == TOO_MANY_REQUESTS || status in TRANSIENT_SERVER_ERRORS
        return ProviderHttpFailureClassification(
            outcomeCode = if (retryable) {
                ReferenceResolutionAttemptOutcomeCode.RETRYABLE_FAILURE
            } else {
                ReferenceResolutionAttemptOutcomeCode.PERMANENT_FAILURE
            },
            retryAfterMillis = if (retryable) ProviderRetryAfter.parseMillis(retryAfterValues, now) else null,
        )
    }

    private const val REQUEST_TIMEOUT = 408
    private const val TOO_MANY_REQUESTS = 429
    private val TRANSIENT_SERVER_ERRORS = setOf(500, 502, 503, 504)
}
