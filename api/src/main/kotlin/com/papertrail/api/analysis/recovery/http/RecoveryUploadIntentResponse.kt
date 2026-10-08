package com.papertrail.api.analysis.recovery.http

import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant

@Schema(description = "A short-lived, single-object upload capability. Treat uploadUrl as a bearer credential; it is not persisted in Recovery Batch history.")
data class RecoveryUploadIntentResponse(
    val upload: RecoveryUploadResponse,
    @field:Schema(description = "Short-lived browser-reachable presigned PUT URL. Treat as a bearer credential; null when the upload is already staged.", format = "uri")
    val uploadUrl: String?,
    @field:Schema(description = "Required browser-set headers. Content-Length is supplied by the browser and must equal expectedSize.")
    val requiredHeaders: Map<String, String>,
    val uploadUrlExpiresAt: Instant?,
)
