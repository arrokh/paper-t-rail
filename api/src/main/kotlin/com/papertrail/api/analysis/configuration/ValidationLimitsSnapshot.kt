package com.papertrail.api.analysis.configuration

data class ValidationLimitsSnapshot(
    val maxUploadBytes: Long,
    val maxPages: Int,
    val maxExtractedCharacters: Int,
    val maxExtractedCharactersPerPage: Int,
    val minimumExtractedCharacters: Int,
    val minimumLanguageConfidence: Double,
)
