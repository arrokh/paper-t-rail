package com.papertrail.api.document.validation

data class PdfValidationLimits(
    val maxBytes: Long,
    val maxPages: Int,
    val maxExtractedCharacters: Int,
    val maxExtractedCharactersPerPage: Int,
    val minimumExtractedCharacters: Int,
    val minimumLanguageConfidence: Double,
)
