package com.papertrail.api.scholarly.references.service

import com.papertrail.api.analysis.configuration.ProviderChainLimitsSnapshot
import org.springframework.stereotype.Component
import java.util.concurrent.ThreadLocalRandom

fun interface ProviderChainRetryDelaySource {
    fun nextRetryDelayMillis(limits: ProviderChainLimitsSnapshot): Long
}

@Component
class JitteredProviderChainRetryDelaySource : ProviderChainRetryDelaySource {
    override fun nextRetryDelayMillis(limits: ProviderChainLimitsSnapshot): Long = ThreadLocalRandom.current()
        .nextLong(limits.retryDelayMinMillis.toLong(), limits.retryDelayMaxMillis.toLong() + 1)
}
