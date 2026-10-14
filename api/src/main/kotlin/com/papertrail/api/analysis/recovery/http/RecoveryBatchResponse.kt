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
    @field:Schema(description = "Current PDF-rights declaration version; null until accepted. DOI-only input does not require this declaration.", nullable = true)
    val rightsDeclarationVersion: String?,
    @field:Schema(description = "Exact accepted PDF-rights declaration text; null until accepted.", nullable = true)
    val rightsDeclarationText: String?,
    @field:Schema(description = "Time the PDF-rights declaration was accepted; null until accepted.", nullable = true)
    val rightsDeclaredAt: Instant?,
    val createdAt: Instant,
    val lastActivityAt: Instant,
    val expiresAt: Instant,
    @field:Schema(description = "Monotonic revision of the current batch contents; successor requests must submit the revision they inspected.", minimum = "0")
    val revision: Long,
    val uploads: List<RecoveryUploadResponse>,
    @field:Schema(description = "Current exact selected assets that remain staged and match their latest validation attempt.")
    val selectedAssets: List<RecoveryUploadAssetSelectionResponse>,
    @field:Schema(description = "Researcher-confirmed DOI assistance currently staged for unresolved supported Bibliography Entries. Identifier values are intentionally excluded from recovery history.")
    val identifierAssistance: List<RecoveryIdentifierAssistanceResponse>,
)
