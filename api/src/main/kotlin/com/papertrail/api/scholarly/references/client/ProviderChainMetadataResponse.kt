package com.papertrail.api.scholarly.references.client

data class ProviderChainMetadataResponse<T>(
    val value: T,
    val httpStatus: Int,
    val responseBytes: Long?,
) {
    init {
        require(httpStatus in 100..599) { "HTTP status must be a valid numeric status." }
        require(responseBytes == null || responseBytes >= 0) { "Response bytes cannot be negative." }
    }
}
