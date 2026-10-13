package com.papertrail.api.analysis.recovery.domain

import java.time.Instant
import java.util.UUID

data class RecoveryUploadIdentifierConfirmation(
    val id: UUID,
    val batchId: UUID,
    val uploadId: UUID,
    val analysisRunId: UUID,
    val validationAttemptId: UUID,
    val contentSha256: String,
    val identifierType: String,
    val identifierValue: String,
    val candidateFingerprint: String,
    val confirmedAt: Instant,
) {
    fun matches(other: RecoveryUploadIdentifierConfirmation): Boolean =
        batchId == other.batchId && uploadId == other.uploadId && analysisRunId == other.analysisRunId &&
            validationAttemptId == other.validationAttemptId && contentSha256 == other.contentSha256 &&
            identifierType == other.identifierType && identifierValue == other.identifierValue &&
            candidateFingerprint == other.candidateFingerprint
}
