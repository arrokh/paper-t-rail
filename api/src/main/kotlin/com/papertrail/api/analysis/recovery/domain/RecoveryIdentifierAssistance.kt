package com.papertrail.api.analysis.recovery.domain

import java.time.Instant
import java.util.UUID

data class RecoveryIdentifierAssistance(
    val id: UUID,
    val batchId: UUID,
    val analysisRunId: UUID,
    val bibliographyEntryId: UUID,
    val localReferenceKey: String,
    val identifierType: String,
    val identifierValue: String,
    val recordedAt: Instant,
)
