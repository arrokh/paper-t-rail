package com.papertrail.api.analysis.configuration

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "Effective server-approved operational limits pinned for an immutable reference-resolution provider chain.")
data class ProviderChainLimitsSnapshot(
    @field:Schema(description = "Maximum Crossref plus DataCite logical lookups per Cited Reference.")
    val maxLogicalLookupsPerReference: Int,
    @field:Schema(description = "Maximum total wire attempts across the chain, including a permitted retry.")
    val maxWireAttemptsPerReference: Int,
    @field:Schema(description = "Maximum retry attempts across the chain.")
    val maxRetriesPerReference: Int,
    @field:Schema(description = "Maximum elapsed time from first persisted reservation, in milliseconds.")
    val deadlineMillis: Long,
    @field:Schema(description = "Maximum number of Crossref candidates returned for title lookup.")
    val crossrefCandidateLimit: Int,
    @field:Schema(description = "Maximum number of DataCite records returned for exact DOI lookup.")
    val dataCiteRecordLimit: Int,
    @field:Schema(description = "Maximum decoded JSON response bytes accepted before parsing.")
    val maxDecodedJsonBytes: Int,
    @field:Schema(description = "Shared request rate limit per provider, in requests per second.")
    val requestsPerSecondPerProvider: Int,
    @field:Schema(description = "Shared maximum in-flight request count per provider across workers.")
    val maxConcurrentRequestsPerProvider: Int,
    @field:Schema(description = "Minimum jittered retry delay, in milliseconds.")
    val retryDelayMinMillis: Int,
    @field:Schema(description = "Maximum jittered retry delay, in milliseconds.")
    val retryDelayMaxMillis: Int,
) {
    init {
        require(maxLogicalLookupsPerReference > 0)
        require(maxWireAttemptsPerReference > 0)
        require(maxRetriesPerReference >= 0)
        require(deadlineMillis > 0)
        require(crossrefCandidateLimit > 0)
        require(dataCiteRecordLimit > 0)
        require(maxDecodedJsonBytes > 0)
        require(requestsPerSecondPerProvider > 0)
        require(maxConcurrentRequestsPerProvider > 0)
        require(retryDelayMinMillis >= 0 && retryDelayMaxMillis >= retryDelayMinMillis)
    }

    companion object {
        val EXACT_DOI_V1 = ProviderChainLimitsSnapshot(
            maxLogicalLookupsPerReference = 2,
            maxWireAttemptsPerReference = 3,
            maxRetriesPerReference = 1,
            deadlineMillis = 80_000,
            crossrefCandidateLimit = 10,
            dataCiteRecordLimit = 1,
            maxDecodedJsonBytes = 1_048_576,
            requestsPerSecondPerProvider = 1,
            maxConcurrentRequestsPerProvider = 1,
            retryDelayMinMillis = 250,
            retryDelayMaxMillis = 2_000,
        )
    }
}
