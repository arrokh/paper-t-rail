package com.papertrail.api.scholarly.references.attempts.domain

import java.time.Instant
import java.util.UUID

class ReferenceResolutionChainAuthorization internal constructor(
    val analysisRunId: UUID,
    val bibliographyEntryId: UUID,
    val ownerToken: UUID,
    val ownerFence: Long,
    leaseExpiresAt: Instant,
) {
    @Volatile
    var leaseExpiresAt: Instant = leaseExpiresAt
        private set

    internal fun refreshLeaseExpiry(expiry: Instant) {
        check(expiry >= leaseExpiresAt) { "A renewed chain lease cannot expire before its previous lease." }
        leaseExpiresAt = expiry
    }
}
