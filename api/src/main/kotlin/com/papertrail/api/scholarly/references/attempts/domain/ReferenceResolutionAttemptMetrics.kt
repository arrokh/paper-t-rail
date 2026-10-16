package com.papertrail.api.scholarly.references.attempts.domain

data class ReferenceResolutionAttemptMetrics(
    val httpStatus: Int? = null,
    val durationMillis: Long? = null,
    val responseBytes: Long? = null,
) {
    init {
        require(httpStatus == null || httpStatus in 100..599)
        require(durationMillis == null || durationMillis >= 0)
        require(responseBytes == null || responseBytes >= 0)
    }
}
