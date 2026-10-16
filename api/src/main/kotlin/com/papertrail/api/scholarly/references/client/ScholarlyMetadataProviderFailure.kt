package com.papertrail.api.scholarly.references.client

import com.papertrail.api.scholarly.references.attempts.domain.ReferenceResolutionAttemptOutcomeCode

open class ScholarlyMetadataProviderFailure(
    val outcomeCode: ReferenceResolutionAttemptOutcomeCode,
    val httpStatus: Int? = null,
    val retryAfterMillis: Long? = null,
    val responseBytes: Long? = null,
    message: String,
) : RuntimeException(message) {
    init {
        require(
            outcomeCode == ReferenceResolutionAttemptOutcomeCode.UNUSABLE ||
                outcomeCode == ReferenceResolutionAttemptOutcomeCode.RETRYABLE_FAILURE ||
                outcomeCode == ReferenceResolutionAttemptOutcomeCode.PERMANENT_FAILURE ||
                outcomeCode == ReferenceResolutionAttemptOutcomeCode.UNKNOWN,
        ) { "A provider failure must use a failure outcome." }
        require(httpStatus == null || httpStatus in 100..599) { "HTTP status must be a valid numeric status." }
        require(retryAfterMillis == null || retryAfterMillis >= 0) { "Retry delay cannot be negative." }
        require(responseBytes == null || responseBytes >= 0) { "Response bytes cannot be negative." }
    }
}
