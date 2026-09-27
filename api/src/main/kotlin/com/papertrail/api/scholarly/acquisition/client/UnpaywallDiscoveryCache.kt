package com.papertrail.api.scholarly.acquisition.client

import com.papertrail.api.scholarly.acquisition.domain.OpenAccessDiscovery
import java.time.Instant

interface UnpaywallDiscoveryCache {
    /** A non-null entry is a hit even when it represents a cached not-found result. */
    fun findByDoi(doi: String): UnpaywallDiscoveryCacheEntry?

    fun storeByDoi(doi: String, discovery: OpenAccessDiscovery?, fetchedAt: Instant)

    fun invalidateDoi(doi: String): Boolean
}
