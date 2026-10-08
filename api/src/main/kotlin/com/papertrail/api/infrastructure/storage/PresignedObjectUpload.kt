package com.papertrail.api.infrastructure.storage

data class PresignedObjectUpload(
    val url: String,
    val contentType: String,
    val checksumSha256: String,
)
