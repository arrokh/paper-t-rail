package com.papertrail.api.analysis.configuration

data class ValidationLimitsSnapshot(
    val maxUploadBytes: Long,
    val maxPages: Int,
    val maxExtractedCharacters: Int,
    val maxExtractedCharactersPerPage: Int,
    val minimumExtractedCharacters: Int,
    val minimumLanguageConfidence: Double,
    val maxClaimCitationPairs: Int = DEFAULT_MAX_CLAIM_CITATION_PAIRS,
) {
    companion object {
        const val DEFAULT_MAX_CLAIM_CITATION_PAIRS = 5_000
    }
}
