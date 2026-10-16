package com.papertrail.api.scholarly.references.client

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ProviderRetryDelayPolicyTest {
    @Test
    fun `honors a valid retry-after when the next attempt still fits the deadline`() {
        assertEquals(
            60_000,
            ProviderRetryDelayPolicy.chooseRetryDelayMillis(
                retryAfterMillis = 60_000,
                jitteredFallbackMillis = 500,
                remainingDeadlineMillis = 80_000,
                nextAttemptBudgetMillis = 20_000,
            ),
        )
    }

    @Test
    fun `declines rather than shortening a valid retry-after that does not fit`() {
        assertNull(
            ProviderRetryDelayPolicy.chooseRetryDelayMillis(
                retryAfterMillis = 70_001,
                jitteredFallbackMillis = 500,
                remainingDeadlineMillis = 80_000,
                nextAttemptBudgetMillis = 10_000,
            ),
        )
    }

    @Test
    fun `uses only bounded jitter when provider gives no valid retry-after`() {
        assertEquals(
            500,
            ProviderRetryDelayPolicy.chooseRetryDelayMillis(
                retryAfterMillis = null,
                jitteredFallbackMillis = 500,
                remainingDeadlineMillis = 1_000,
                nextAttemptBudgetMillis = 500,
            ),
        )
        assertNull(
            ProviderRetryDelayPolicy.chooseRetryDelayMillis(
                retryAfterMillis = null,
                jitteredFallbackMillis = 2_001,
                remainingDeadlineMillis = 10_000,
                nextAttemptBudgetMillis = 1_000,
            ),
        )
    }

    @Test
    fun `declines a retry when there is no time for delay and a bounded provider call`() {
        assertNull(
            ProviderRetryDelayPolicy.chooseRetryDelayMillis(
                retryAfterMillis = null,
                jitteredFallbackMillis = 250,
                remainingDeadlineMillis = 1_000,
                nextAttemptBudgetMillis = 800,
            ),
        )
    }
}
