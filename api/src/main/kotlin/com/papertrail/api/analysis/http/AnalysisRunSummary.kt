package com.papertrail.api.analysis.http

import com.fasterxml.jackson.databind.JsonNode
import com.papertrail.api.analysis.configuration.AnalysisConfigurationSnapshot
import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant
import java.util.UUID

data class AnalysisRunSummary(
    val id: UUID,
    val documentId: UUID,
    val filename: String,
    val sourceContentSha256: String,
    @field:Schema(description = "PARSED means citation structure, Atomic Claims, inferred Citation Target links, reference-resolution outcomes, and eligible Cited Paper Evidence Passage retrieval are ready. Final Claim–Paper Verification is not complete; a local Laya evaluation may have recorded uncalibrated Evidence Judgements without aggregation.")
    val status: String,
    @field:Schema(implementation = AnalysisRunProgress::class, description = "Persisted run progress.")
    val progress: JsonNode,
    @field:Schema(description = "Per-stage and per-item worker execution state. This is separate from domain outcomes and does not treat the Evidence Coverage Report projection as a worker stage.")
    val pipeline: AnalysisRunPipelineProgress? = null,
    @field:Schema(implementation = AnalysisConfigurationSnapshot::class, description = "Immutable configuration and provenance snapshot for this run.")
    val configuration: JsonNode,
    val createdAt: Instant,
    val startedAt: Instant?,
    @field:Schema(description = "Terminal processing or validation failure. Claim-citation pair limit failures include the observed pair count and configured limit.")
    val failureReason: String?,
)
