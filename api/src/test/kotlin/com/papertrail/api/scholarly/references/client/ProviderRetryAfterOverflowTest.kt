package com.papertrail.api.scholarly.references.client

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Instant

class ProviderRetryAfterOverflowTest {
    @Test
    fun `valid delta seconds beyond millisecond range never falls back to jitter`() {
        listOf("9223372036854776", "9999999999999999999999999999999999", "9".repeat(65)).forEach { header ->
            val retryAfterMillis = ProviderRetryAfter.parseMillis(listOf(header), Instant.EPOCH)

            assertEquals(Long.MAX_VALUE, retryAfterMillis)
            assertNull(
                ProviderRetryDelayPolicy.chooseRetryDelayMillis(
                    retryAfterMillis = retryAfterMillis,
                    jitteredFallbackMillis = 500,
                    remainingDeadlineMillis = 80_000,
                    nextAttemptBudgetMillis = 10_000,
                ),
            )
        }
    }
}
