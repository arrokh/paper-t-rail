package com.papertrail.api.scholarly.acquisition.client

import com.papertrail.api.scholarly.acquisition.domain.OpenAccessDiscovery
import java.time.Instant
import com.papertrail.api.external.unpaywall.UnpaywallDiscoveryCache
import com.papertrail.api.external.unpaywall.UnpaywallDiscoveryCacheEntry

class NoOpUnpaywallDiscoveryCache : UnpaywallDiscoveryCache {
    override fun findByDoi(doi: String): UnpaywallDiscoveryCacheEntry? = null

    override fun storeByDoi(doi: String, discovery: OpenAccessDiscovery?, fetchedAt: Instant) = Unit

    override fun invalidateDoi(doi: String): Boolean = false
}
