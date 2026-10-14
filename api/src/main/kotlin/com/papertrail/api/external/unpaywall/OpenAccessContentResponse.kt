package com.papertrail.api.external.unpaywall

data class OpenAccessContentResponse(
    val statusCode: Int,
    val mediaType: String?,
    val bytes: ByteArray,
)
