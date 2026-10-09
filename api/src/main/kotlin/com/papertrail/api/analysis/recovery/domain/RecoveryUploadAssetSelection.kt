package com.papertrail.api.analysis.recovery.domain

import java.time.Instant
import java.util.UUID

data class RecoveryUploadAssetSelection(
    val id: UUID,
    val batchId: UUID,
    val analysisRunId: UUID,
    val bibliographyEntryId: UUID,
    val uploadId: UUID,
    val validationAttemptId: UUID,
    val contentSha256: String,
    val selectionMethod: RecoveryAssetSelectionMethod,
    val selectedAt: Instant,
)
