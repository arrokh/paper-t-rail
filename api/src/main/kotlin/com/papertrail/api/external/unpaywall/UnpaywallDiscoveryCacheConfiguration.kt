package com.papertrail.api.external.unpaywall

import com.papertrail.api.infrastructure.cache.ProviderCacheStore
import com.papertrail.api.external.unpaywall.RedisUnpaywallDiscoveryCache
import com.papertrail.api.external.unpaywall.UnpaywallDiscoveryCache
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Duration

@Configuration
class UnpaywallDiscoveryCacheConfiguration {
    @Bean
    fun unpaywallDiscoveryCache(
        cacheStore: ProviderCacheStore,
        @Value("\${paper-trail.providers.unpaywall.cache.positive-ttl:24h}") positiveTtl: Duration,
        @Value("\${paper-trail.providers.unpaywall.cache.negative-ttl:1h}") negativeTtl: Duration,
    ): UnpaywallDiscoveryCache = RedisUnpaywallDiscoveryCache(cacheStore, positiveTtl, negativeTtl)
}
