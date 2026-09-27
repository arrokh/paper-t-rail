package com.papertrail.api.scholarly.acquisition.client

import com.papertrail.api.scholarly.acquisition.domain.OpenAccessLocation

data class UnpaywallCachedLocation(
    val url: String,
    val license: String?,
    val version: String?,
    val hostType: String?,
) {
    fun toLocation(): OpenAccessLocation = OpenAccessLocation(
        url = url,
        license = license,
        version = version,
        hostType = hostType,
        providerId = UnpaywallOpenAccessProviderFactory.UNPAYWALL_PROVIDER,
    )

    companion object {
        fun from(location: OpenAccessLocation): UnpaywallCachedLocation = UnpaywallCachedLocation(
            url = location.url,
            license = location.license,
            version = location.version,
            hostType = location.hostType,
        )
    }
}
