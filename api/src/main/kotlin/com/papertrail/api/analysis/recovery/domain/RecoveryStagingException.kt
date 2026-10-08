package com.papertrail.api.analysis.recovery.domain

class RecoveryStagingException(
    val code: String,
    val statusCode: Int,
    message: String,
) : RuntimeException(message)
