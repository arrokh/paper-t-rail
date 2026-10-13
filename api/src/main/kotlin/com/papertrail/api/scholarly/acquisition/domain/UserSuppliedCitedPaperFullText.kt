package com.papertrail.api.scholarly.acquisition.domain

data class UserSuppliedCitedPaperFullText(
    val bytes: ByteArray,
    val mediaType: String,
)
