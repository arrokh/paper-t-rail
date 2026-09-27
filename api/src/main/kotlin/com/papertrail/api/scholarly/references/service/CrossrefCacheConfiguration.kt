package com.papertrail.api.scholarly.references.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.papertrail.api.scholarly.references.client.CrossrefLookupCache
import com.papertrail.api.scholarly.references.client.RedisCrossrefLookupCache
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.core.StringRedisTemplate
import java.time.Duration

@Configuration
class CrossrefCacheConfiguration {
    @Bean
    fun crossrefLookupCache(
        redis: StringRedisTemplate,
        objectMapper: ObjectMapper,
        @Value("\${paper-trail.providers.crossref.cache.positive-ttl:30d}") positiveTtl: Duration,
        @Value("\${paper-trail.providers.crossref.cache.negative-ttl:1h}") negativeTtl: Duration,
    ): CrossrefLookupCache = RedisCrossrefLookupCache(redis, objectMapper, positiveTtl, negativeTtl)
}
