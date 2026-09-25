package com.papertrail.api.analysis.http

import java.time.Instant
import java.util.UUID

data class CreatedAnalysisRunResponse(
    val documentId: UUID,
    val analysisRunId: UUID,
    val filename: String,
    val sourceContentSha256: String,
    val status: String,
    val createdAt: Instant,
)
