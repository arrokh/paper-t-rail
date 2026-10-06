package com.papertrail.api.external.unpaywall

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import com.papertrail.api.infrastructure.cache.ProviderCacheStore
import com.papertrail.api.scholarly.acquisition.domain.OpenAccessDiscovery
import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessException
import java.time.Duration
import java.time.Instant

class RedisUnpaywallDiscoveryCache(
    private val cacheStore: ProviderCacheStore,
    private val objectMapper: ObjectMapper,
    private val positiveTtl: Duration,
    private val negativeTtl: Duration,
) : UnpaywallDiscoveryCache {
    init {
        require(!positiveTtl.isNegative && !positiveTtl.isZero) { "Unpaywall positive cache TTL must be greater than zero." }
        require(!negativeTtl.isNegative && !negativeTtl.isZero) { "Unpaywall negative cache TTL must be greater than zero." }
    }

    override fun findByDoi(doi: String): UnpaywallDiscoveryCacheEntry? =
        UnpaywallCacheKeys.doi(doi)?.let(::find)

    override fun storeByDoi(doi: String, discovery: OpenAccessDiscovery?, fetchedAt: Instant) {
        val key = UnpaywallCacheKeys.doi(doi) ?: return
        val entry = UnpaywallDiscoveryCacheEntry.from(discovery, fetchedAt)
        val ttl = if (entry.hasPositiveResult()) positiveTtl else negativeTtl
        cacheStore.store(key, objectMapper.writeValueAsString(entry), ttl)
    }

    override fun invalidateDoi(doi: String): Boolean =
        UnpaywallCacheKeys.doi(doi)?.let(cacheStore::invalidate) ?: false

    private fun find(key: String): UnpaywallDiscoveryCacheEntry? {
        val encoded = cacheStore.find(key) ?: return null
        return try {
            val entry: UnpaywallDiscoveryCacheEntry? = objectMapper.readValue(encoded, UnpaywallDiscoveryCacheEntry::class.java)
            if (entry == null || entry.schemaVersion != UnpaywallDiscoveryCacheEntry.CURRENT_SCHEMA_VERSION ||
                entry.locations.any { it.url.isBlank() }
            ) {
                removeInvalidEntry(key)
                null
            } else {
                entry
            }
        } catch (_: JsonProcessingException) {
            removeInvalidEntry(key)
            null
        } catch (_: IllegalArgumentException) {
            removeInvalidEntry(key)
            null
        }
    }

    private fun removeInvalidEntry(key: String) {
        try {
            cacheStore.invalidate(key)
        } catch (_: DataAccessException) {
            log.warn("Invalid Unpaywall cache entry could not be removed")
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(RedisUnpaywallDiscoveryCache::class.java)
    }
}
