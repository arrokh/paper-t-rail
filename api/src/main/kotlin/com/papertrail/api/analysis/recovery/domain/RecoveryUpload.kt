package com.papertrail.api.analysis.recovery.domain

import java.time.Instant
import java.util.UUID

data class RecoveryUpload(
    val id: UUID,
    val batchId: UUID,
    val analysisRunId: UUID,
    val bibliographyEntryId: UUID,
    val localReferenceKey: String,
    val idempotencyKey: UUID,
    val filename: String,
    val expectedSize: Long,
    val expectedSha256: String,
    val stagingObjectKey: String,
    val finalizedObjectKey: String,
    val status: RecoveryUploadStatus,
    val failureCode: String?,
    val presignedUrlExpiresAt: Instant,
    val actualSize: Long?,
    val actualSha256: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val finalizedAt: Instant?,
)
