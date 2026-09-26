package com.papertrail.api.scholarly.acquisition.domain

data class AcquiredFullText(
    val bytes: ByteArray,
    val mediaType: String,
    val location: OpenAccessLocation,
)
