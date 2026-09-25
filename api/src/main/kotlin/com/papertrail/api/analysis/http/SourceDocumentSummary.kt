package com.papertrail.api.analysis.http

import java.time.Instant
import java.util.UUID

data class SourceDocumentSummary(
    val id: UUID,
    val filename: String,
    val sha256: String,
    val pageCount: Int,
    val language: String,
    val createdAt: Instant,
)
