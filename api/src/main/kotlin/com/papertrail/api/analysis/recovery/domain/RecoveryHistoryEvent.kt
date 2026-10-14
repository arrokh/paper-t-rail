package com.papertrail.api.analysis.recovery.domain

import java.time.Instant
import java.util.UUID

data class RecoveryHistoryEvent(
    val id: UUID,
    val batchId: UUID,
    val analysisRunId: UUID,
    val batchStatus: String,
    val eventType: String,
    val outcomeCode: String,
    val stageCode: String?,
    val attemptNumber: Int?,
    val bibliographyEntryId: UUID?,
    val uploadId: UUID?,
    val previousUploadId: UUID?,
    val successorAnalysisRunId: UUID?,
    val successorStatus: String?,
    val selectedEntryCount: Int?,
    val identifierAssistanceEntryCount: Int?,
    val resolutionQueuedEntryCount: Int?,
    val createdAt: Instant,
)
