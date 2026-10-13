package com.papertrail.api.analysis.recovery.http

import com.papertrail.api.analysis.recovery.domain.RecoveryIdentityConfirmation
import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant
import java.util.UUID

data class RecoveryIdentityConfirmationResponse(
    val id: UUID,
    val batchId: UUID,
    val uploadId: UUID,
    val validationAttemptId: UUID,
    val contentSha256: String,
    val decision: String,
    val confirmedAt: Instant,
    @field:Schema(description = "Optional, separate confirmation of one normalized DOI candidate and its exact source provenance.", nullable = true)
    val identifierConfirmation: RecoveryUploadIdentifierConfirmationResponse?,
) {
    companion object {
        fun from(confirmation: RecoveryIdentityConfirmation) = RecoveryIdentityConfirmationResponse(
            id = confirmation.id,
            batchId = confirmation.batchId,
            uploadId = confirmation.uploadId,
            validationAttemptId = confirmation.validationAttemptId,
            contentSha256 = confirmation.contentSha256,
            decision = confirmation.decision,
            confirmedAt = confirmation.confirmedAt,
            identifierConfirmation = confirmation.identifierConfirmation?.let(RecoveryUploadIdentifierConfirmationResponse::from),
        )
    }
}
