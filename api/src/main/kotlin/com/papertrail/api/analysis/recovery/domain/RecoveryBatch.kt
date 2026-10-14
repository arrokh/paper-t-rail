package com.papertrail.api.analysis.recovery.domain

import java.time.Instant
import java.util.UUID

data class RecoveryBatch(
    val id: UUID,
    val analysisRunId: UUID,
    val status: RecoveryBatchStatus,
    val rightsDeclaration: RecoveryRightsDeclaration?,
    val rightsDeclaredAt: Instant?,
    val createdAt: Instant,
    val lastActivityAt: Instant,
    val expiresAt: Instant,
    val revision: Long,
    val uploads: List<RecoveryUpload>,
    val selectedAssets: List<RecoveryUploadAssetSelection>,
    val identifierAssistance: List<RecoveryIdentifierAssistance> = emptyList(),
)
