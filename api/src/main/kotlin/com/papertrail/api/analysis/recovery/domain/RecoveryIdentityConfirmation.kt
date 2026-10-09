package com.papertrail.api.analysis.recovery.domain

import java.time.Instant
import java.util.UUID

data class RecoveryIdentityConfirmation(
    val id: UUID,
    val batchId: UUID,
    val uploadId: UUID,
    val validationAttemptId: UUID,
    val contentSha256: String,
    val decision: String,
    val confirmedAt: Instant,
)
