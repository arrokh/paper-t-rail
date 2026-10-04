package com.papertrail.api.analysis.http

import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant

@Schema(description = "Short-lived view and download links for the original uploaded PDF.")
data class AnalysisRunSourcePdfAccess(
    @Schema(description = "Original upload filename.")
    val filename: String,
    @Schema(description = "Read-only S3-compatible presigned bearer URL for inline PDF viewing; valid until expiresAt.", format = "uri")
    val viewUrl: String,
    @Schema(description = "Read-only S3-compatible presigned bearer URL that downloads the PDF as an attachment; valid until expiresAt.", format = "uri")
    val downloadUrl: String,
    @Schema(description = "Time when both signed URLs expire.", format = "date-time")
    val expiresAt: Instant,
)
