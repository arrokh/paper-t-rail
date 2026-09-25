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
    @field:Schema(description = "PARSED means parsed citation structure, Atomic Claims, inferred Citation Target links, and conservative bibliography-resolution outcomes are persisted; evidence verification has not run.")
    val status: String,
    @field:Schema(implementation = AnalysisRunProgress::class, description = "Persisted run progress.")
    val progress: JsonNode,
    @field:Schema(implementation = AnalysisConfigurationSnapshot::class, description = "Immutable configuration and provenance snapshot for this run.")
    val configuration: JsonNode,
    val createdAt: Instant,
    val startedAt: Instant?,
    val failureReason: String?,
)
