package com.papertrail.api.evidence.verification.provider

open class SystemOneProviderException(
    message: String,
    val failureReasonCode: String? = null,
) : IllegalStateException(message)
