package com.papertrail.api.analysis.recovery.http

import com.papertrail.api.analysis.recovery.domain.RecoveryUploadAssetSelection
import java.time.Instant
import java.util.UUID

data class RecoveryUploadAssetSelectionResponse(
    val id: UUID,
    val batchId: UUID,
    val analysisRunId: UUID,
    val bibliographyEntryId: UUID,
    val uploadId: UUID,
    val validationAttemptId: UUID,
    val contentSha256: String,
    val selectionMethod: String,
    val selectedAt: Instant,
) {
    companion object {
        fun from(selection: RecoveryUploadAssetSelection) = RecoveryUploadAssetSelectionResponse(
            id = selection.id,
            batchId = selection.batchId,
            analysisRunId = selection.analysisRunId,
            bibliographyEntryId = selection.bibliographyEntryId,
            uploadId = selection.uploadId,
            validationAttemptId = selection.validationAttemptId,
            contentSha256 = selection.contentSha256,
            selectionMethod = selection.selectionMethod.name,
            selectedAt = selection.selectedAt,
        )
    }
}
