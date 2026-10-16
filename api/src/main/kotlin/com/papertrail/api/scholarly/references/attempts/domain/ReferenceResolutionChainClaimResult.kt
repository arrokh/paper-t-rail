package com.papertrail.api.scholarly.references.attempts.domain

import java.time.Instant

sealed interface ReferenceResolutionChainClaimResult {
    data class Claimed(
        val authorization: ReferenceResolutionChainAuthorization,
        val recoveredReservation: ReferenceResolutionAttemptReservation?,
        val uncertainAttempt: ReferenceResolutionAttemptReservation? = null,
    ) : ReferenceResolutionChainClaimResult

    data class Deferred(
        val until: Instant,
    ) : ReferenceResolutionChainClaimResult

    data class AlreadyFinalized(
        val outcome: ReferenceResolutionChainOutcome?,
        val metadataOriginProviderId: String?,
    ) : ReferenceResolutionChainClaimResult

    data object MissingEntry : ReferenceResolutionChainClaimResult
}
