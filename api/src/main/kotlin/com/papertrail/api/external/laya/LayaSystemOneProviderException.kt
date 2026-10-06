package com.papertrail.api.external.laya

import com.papertrail.api.evidence.verification.provider.SystemOneProviderException

class LayaSystemOneProviderException(
    message: String,
    failureReasonCode: String? = null,
    val measuredSequenceTokenCounts: List<Int> = emptyList(),
) : SystemOneProviderException(message, failureReasonCode) {
    companion object {
        const val CONTEXT_LIMIT_EXCEEDED = "SYSTEM_ONE_CONTEXT_LIMIT_EXCEEDED"
        const val REQUEST_REJECTED = "SYSTEM_ONE_REQUEST_REJECTED"
    }
}
