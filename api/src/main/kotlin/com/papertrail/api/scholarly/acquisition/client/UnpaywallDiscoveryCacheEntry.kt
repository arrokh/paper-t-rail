package com.papertrail.api.scholarly.acquisition.client

import com.papertrail.api.scholarly.acquisition.domain.OpenAccessDiscovery
import java.time.Instant

data class UnpaywallDiscoveryCacheEntry(
    val schemaVersion: Int,
    val fetchedAt: Instant,
    val metadataAvailable: Boolean,
    val abstractAvailable: Boolean,
    val locations: List<UnpaywallCachedLocation>,
) {
    fun hasPositiveResult(): Boolean = metadataAvailable || abstractAvailable || locations.isNotEmpty()

    fun toDiscovery(): OpenAccessDiscovery? {
        if (!hasPositiveResult()) return null
        return OpenAccessDiscovery(
            metadataAvailable = metadataAvailable,
            abstractAvailable = abstractAvailable,
            locations = locations.map(UnpaywallCachedLocation::toLocation),
            providerId = UnpaywallOpenAccessProviderFactory.UNPAYWALL_PROVIDER,
            discoveredAt = fetchedAt,
        )
    }

    companion object {
        const val CURRENT_SCHEMA_VERSION = 1

        fun from(discovery: OpenAccessDiscovery?, fetchedAt: Instant): UnpaywallDiscoveryCacheEntry =
            UnpaywallDiscoveryCacheEntry(
                schemaVersion = CURRENT_SCHEMA_VERSION,
                fetchedAt = discovery?.discoveredAt ?: fetchedAt,
                metadataAvailable = discovery?.metadataAvailable == true,
                abstractAvailable = discovery?.abstractAvailable == true,
                locations = discovery?.locations.orEmpty().map(UnpaywallCachedLocation::from),
            )
    }
}
