package com.papertrail.api.analysis.recovery.http

import com.papertrail.api.analysis.recovery.domain.RecoveryHistoryEvent
import com.papertrail.api.analysis.recovery.repository.RecoveryHistoryPage
import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant
import java.util.UUID

@Schema(description = "Newest-first, keyset-paginated content-minimized recovery history for a predecessor Analysis Run.")
data class RecoveryHistoryResponse(
    @field:Schema(description = "Predecessor Analysis Run identifier.", format = "uuid")
    val analysisRunId: UUID,
    val events: List<RecoveryHistoryEventResponse>,
    @field:Schema(description = "Opaque keyset cursor for the next older page, or null when complete.", nullable = true)
    val nextCursor: String?,
) {
    companion object {
        fun from(page: RecoveryHistoryPage) = RecoveryHistoryResponse(
            analysisRunId = page.analysisRunId,
            events = page.events.map(RecoveryHistoryEventResponse::from),
            nextCursor = page.nextCursor,
        )
    }
}

@Schema(description = "Content-minimized append-only lifecycle, selection, rights, submission, or work event; no DOI value, filename, checksum, PDF bytes, source text, signed URL, or candidate payload is stored.")
data class RecoveryHistoryEventResponse(
    @field:Schema(description = "Stable event identifier.", format = "uuid")
    val id: UUID,
    @field:Schema(description = "Recovery Batch identifier.", format = "uuid")
    val batchId: UUID,
    val batchStatus: String,
    @field:Schema(description = "Stable lifecycle or work event code.")
    val type: String,
    @field:Schema(description = "Stable coarse outcome or reason code.")
    val outcomeCode: String,
    @field:Schema(description = "Stable pipeline stage code, when applicable.", nullable = true)
    val stageCode: String?,
    @field:Schema(description = "One-based attempt number, when applicable.", nullable = true, minimum = "1")
    val attemptNumber: Int?,
    @field:Schema(description = "Predecessor Bibliography Entry identifier, when applicable.", nullable = true, format = "uuid")
    val bibliographyEntryId: UUID?,
    @field:Schema(description = "New/current Recovery Upload identifier, when applicable.", nullable = true, format = "uuid")
    val uploadId: UUID?,
    @field:Schema(description = "Previously selected Recovery Upload identifier for a replacement selection.", nullable = true, format = "uuid")
    val previousUploadId: UUID?,
    val submission: RecoveryHistorySubmissionResponse?,
    val createdAt: Instant,
) {
    companion object {
        fun from(event: RecoveryHistoryEvent) = RecoveryHistoryEventResponse(
            id = event.id,
            batchId = event.batchId,
            batchStatus = event.batchStatus,
            type = event.eventType,
            outcomeCode = event.outcomeCode,
            stageCode = event.stageCode,
            attemptNumber = event.attemptNumber,
            bibliographyEntryId = event.bibliographyEntryId,
            uploadId = event.uploadId,
            previousUploadId = event.previousUploadId,
            submission = event.successorAnalysisRunId?.let { successorId ->
                RecoveryHistorySubmissionResponse(
                    successorAnalysisRunId = successorId,
                    status = event.successorStatus ?: "PROCESSING",
                    selectedEntryCount = event.selectedEntryCount ?: 0,
                    identifierAssistanceEntryCount = event.identifierAssistanceEntryCount ?: 0,
                    resolutionQueuedEntryCount = event.resolutionQueuedEntryCount ?: 0,
                )
            },
            createdAt = event.createdAt,
        )
    }
}

@Schema(description = "Content-minimized successor submission lineage and work counts.")
data class RecoveryHistorySubmissionResponse(
    @field:Schema(description = "Fresh successor Analysis Run identifier.", format = "uuid")
    val successorAnalysisRunId: UUID,
    val status: String,
    val selectedEntryCount: Int,
    val identifierAssistanceEntryCount: Int,
    val resolutionQueuedEntryCount: Int,
)
