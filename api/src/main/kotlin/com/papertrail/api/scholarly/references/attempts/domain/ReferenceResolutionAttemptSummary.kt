package com.papertrail.api.scholarly.references.attempts.domain

import java.time.Instant
import java.util.UUID

data class ReferenceResolutionAttemptSummary(
    val attemptId: UUID,
    val providerId: String,
    val lookupOrdinal: Int,
    val reservationOrdinal: Int,
    val providerAttemptOrdinal: Int,
    val ownerToken: UUID,
    val ownerFence: Long,
    val state: ReferenceResolutionAttemptState,
    val outcomeCode: ReferenceResolutionAttemptOutcomeCode?,
    val httpStatus: Int?,
    val durationMillis: Long?,
    val responseBytes: Long?,
    val reservedAt: Instant,
    val startedAt: Instant?,
    val completedAt: Instant?,
    val unknownAt: Instant?,
    val deadlineAt: Instant,
    val nextAttemptNotBefore: Instant? = null,
)
