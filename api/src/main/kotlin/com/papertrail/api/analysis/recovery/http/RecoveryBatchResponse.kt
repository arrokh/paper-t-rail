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
    val uploads: List<RecoveryUploadResponse>,
)
