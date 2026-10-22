package com.papertrail.api.analysis.http

import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant
import java.util.UUID

@Schema(description = "Short-lived access to the exact stored Cited Paper PDF asset pinned by an Analysis Run.")
data class AnalysisRunCitedPaperPdfAccess(
    val assetId: UUID,
    val contentSha256: String,
    val filename: String,
    @field:Schema(description = "Short-lived S3-compatible presigned bearer URL for inline viewing. Do not persist or log this URL.")
    val viewUrl: String,
    @field:Schema(description = "Short-lived S3-compatible presigned bearer URL for downloading. Do not persist or log this URL.")
    val downloadUrl: String,
    val expiresAt: Instant,
)
