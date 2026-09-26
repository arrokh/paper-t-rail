package com.papertrail.api.scholarly.acquisition.domain

import java.time.Instant

data class OpenAccessDiscovery(
    val metadataAvailable: Boolean,
    val abstractAvailable: Boolean,
    val locations: List<OpenAccessLocation>,
    val providerId: String,
    val discoveredAt: Instant,
)
