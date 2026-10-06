package com.papertrail.api.document.validation

data class ValidatedPdf(
    val sanitizedFilename: String,
    val sha256: String,
    val pageCount: Int,
    val language: String,
    val extractedCharacterCount: Int,
)
