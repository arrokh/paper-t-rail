package com.papertrail.api.scholarly.references.service

import java.time.Instant

sealed interface ProviderChainResolutionDisposition {
    data object Finalized : ProviderChainResolutionDisposition
    data class Deferred(val until: Instant) : ProviderChainResolutionDisposition
}
