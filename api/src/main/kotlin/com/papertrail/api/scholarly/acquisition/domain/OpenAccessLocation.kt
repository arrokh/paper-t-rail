package com.papertrail.api.scholarly.acquisition.domain

data class OpenAccessLocation(
    val url: String,
    val license: String?,
    val version: String?,
    val hostType: String?,
    val providerId: String,
)
