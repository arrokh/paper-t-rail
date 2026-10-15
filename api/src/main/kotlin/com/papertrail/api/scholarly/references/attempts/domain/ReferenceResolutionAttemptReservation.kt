package com.papertrail.api.scholarly.references.attempts.domain

import java.time.Instant
import java.util.UUID

class ReferenceResolutionAttemptReservation internal constructor(
    val attemptId: UUID,
    val analysisRunId: UUID,
    val bibliographyEntryId: UUID,
    val providerId: String,
    val lookupOrdinal: Int,
    val reservationOrdinal: Int,
    val providerAttemptOrdinal: Int,
    val ownerToken: UUID,
    val ownerFence: Long,
    val leaseExpiresAt: Instant,
    val reservedAt: Instant,
    val deadlineAt: Instant,
)
