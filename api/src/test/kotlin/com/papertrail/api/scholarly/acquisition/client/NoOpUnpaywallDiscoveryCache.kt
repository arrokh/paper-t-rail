package com.papertrail.api.scholarly.acquisition.client

import com.papertrail.api.scholarly.acquisition.domain.OpenAccessDiscovery
import java.time.Instant

class NoOpUnpaywallDiscoveryCache : UnpaywallDiscoveryCache {
    override fun findByDoi(doi: String): UnpaywallDiscoveryCacheEntry? = null

    override fun storeByDoi(doi: String, discovery: OpenAccessDiscovery?, fetchedAt: Instant) = Unit

    override fun invalidateDoi(doi: String): Boolean = false
}
