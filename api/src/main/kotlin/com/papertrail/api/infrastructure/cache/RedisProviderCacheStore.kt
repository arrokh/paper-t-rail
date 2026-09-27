package com.papertrail.api.infrastructure.cache

import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessException
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.time.Duration

@Component
class RedisProviderCacheStore(
    private val redis: StringRedisTemplate,
) : ProviderCacheStore {
    override fun find(key: String): String? = try {
        redis.opsForValue().get(key)
    } catch (_: DataAccessException) {
        log.warn("Provider cache read failed; continuing without cached results")
        null
    }

    override fun store(key: String, value: String, ttl: Duration) {
        try {
            redis.opsForValue().set(key, value, ttl)
        } catch (_: DataAccessException) {
            log.warn("Provider cache write failed; continuing without cached results")
        }
    }

    override fun invalidate(key: String): Boolean = redis.delete(key) == true

    companion object {
        private val log = LoggerFactory.getLogger(RedisProviderCacheStore::class.java)
    }
}
