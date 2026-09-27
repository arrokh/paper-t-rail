package com.papertrail.api.infrastructure.cache

import java.time.Duration

/** Shared Redis-backed storage boundary for provider-specific, versioned cache entries. */
interface ProviderCacheStore {
    fun find(key: String): String?

    fun store(key: String, value: String, ttl: Duration)

    fun invalidate(key: String): Boolean
}
