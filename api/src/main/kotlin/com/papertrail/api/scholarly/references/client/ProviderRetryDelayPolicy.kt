package com.papertrail.api.scholarly.references.client

internal object ProviderRetryDelayPolicy {
    fun chooseRetryDelayMillis(
        retryAfterMillis: Long?,
        jitteredFallbackMillis: Long,
        remainingDeadlineMillis: Long,
        nextAttemptBudgetMillis: Long,
    ): Long? {
        if (remainingDeadlineMillis < 0 || nextAttemptBudgetMillis < 0 || nextAttemptBudgetMillis > remainingDeadlineMillis) {
            return null
        }
        val delayMillis = retryAfterMillis ?: jitteredFallbackMillis
        if (delayMillis < 0) return null
        if (retryAfterMillis == null && delayMillis !in MIN_JITTERED_DELAY_MILLIS..MAX_JITTERED_DELAY_MILLIS) return null

        val availableDelayMillis = remainingDeadlineMillis - nextAttemptBudgetMillis
        return delayMillis.takeIf { it <= availableDelayMillis }
    }

    private const val MIN_JITTERED_DELAY_MILLIS = 250L
    private const val MAX_JITTERED_DELAY_MILLIS = 2_000L
}
