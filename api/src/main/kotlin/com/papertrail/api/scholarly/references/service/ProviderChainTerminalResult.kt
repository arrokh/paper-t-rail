package com.papertrail.api.scholarly.references.service

import com.papertrail.api.scholarly.references.attempts.domain.ReferenceResolutionChainOutcome
import com.papertrail.api.scholarly.references.resolver.ReferenceResolutionDecision

data class ProviderChainTerminalResult(
    val decision: ReferenceResolutionDecision,
    val outcome: ReferenceResolutionChainOutcome,
    val metadataOriginProviderId: String?,
)
