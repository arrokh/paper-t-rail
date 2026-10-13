package com.papertrail.api.analysis.recovery.http

import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant
import java.util.UUID

data class RecoveryBatchResponse(
    @field:Schema(description = "Recovery Batch identifier.", format = "uuid")
    val id: UUID,
    @field:Schema(description = "The immutable predecessor Analysis Run.", format = "uuid")
    val analysisRunId: UUID,
    val status: String,
    val rightsDeclarationVersion: String,
    val rightsDeclarationText: String,
    val rightsDeclaredAt: Instant,
    val createdAt: Instant,
    val lastActivityAt: Instant,
    val expiresAt: Instant,
    @field:Schema(description = "Monotonic revision of the current batch contents; successor requests must submit the revision they inspected.", minimum = "0")
    val revision: Long,
    val uploads: List<RecoveryUploadResponse>,
    @field:Schema(description = "Current exact selected assets that remain staged and match their latest validation attempt.")
    val selectedAssets: List<RecoveryUploadAssetSelectionResponse>,
)
