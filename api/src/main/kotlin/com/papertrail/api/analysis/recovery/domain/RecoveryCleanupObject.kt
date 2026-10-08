package com.papertrail.api.analysis.recovery.domain

import java.time.Instant
import java.util.UUID

data class RecoveryCleanupObject(
    val id: UUID,
    val objectKey: String,
    val retryUntil: Instant,
)
