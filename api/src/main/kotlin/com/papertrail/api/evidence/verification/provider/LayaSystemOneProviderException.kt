package com.papertrail.api.evidence.verification.provider

class LayaSystemOneProviderException(
    message: String,
    val failureReasonCode: String? = null,
    val measuredSequenceTokenCounts: List<Int> = emptyList(),
) : IllegalStateException(message) {
    companion object {
        const val CONTEXT_LIMIT_EXCEEDED = "SYSTEM_ONE_CONTEXT_LIMIT_EXCEEDED"
        const val REQUEST_REJECTED = "SYSTEM_ONE_REQUEST_REJECTED"
    }
}
