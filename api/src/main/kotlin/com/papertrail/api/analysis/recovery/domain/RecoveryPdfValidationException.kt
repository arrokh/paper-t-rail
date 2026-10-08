package com.papertrail.api.analysis.recovery.domain

class RecoveryPdfValidationException(
    val code: String,
    message: String,
) : RuntimeException(message)
