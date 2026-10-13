package com.papertrail.api.analysis.recovery.http

import com.papertrail.api.analysis.recovery.domain.RecoveryUploadIdentifierConfirmation
import java.time.Instant
import java.util.UUID

data class RecoveryUploadIdentifierConfirmationResponse(
    val id: UUID,
    val batchId: UUID,
    val uploadId: UUID,
    val validationAttemptId: UUID,
    val contentSha256: String,
    val identifierType: String,
    val identifierValue: String,
    val candidateFingerprint: String,
    val confirmedAt: Instant,
) {
    companion object {
        fun from(confirmation: RecoveryUploadIdentifierConfirmation) = RecoveryUploadIdentifierConfirmationResponse(
            id = confirmation.id,
            batchId = confirmation.batchId,
            uploadId = confirmation.uploadId,
            validationAttemptId = confirmation.validationAttemptId,
            contentSha256 = confirmation.contentSha256,
            identifierType = confirmation.identifierType,
            identifierValue = confirmation.identifierValue,
            candidateFingerprint = confirmation.candidateFingerprint,
            confirmedAt = confirmation.confirmedAt,
        )
    }
}
