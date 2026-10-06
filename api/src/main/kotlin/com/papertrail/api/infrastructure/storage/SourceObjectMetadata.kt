package com.papertrail.api.infrastructure.storage

data class SourceObjectMetadata(
    val size: Long,
    val sha256: String?,
)
