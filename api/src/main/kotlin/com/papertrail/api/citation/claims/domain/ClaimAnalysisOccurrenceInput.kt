package com.papertrail.api.citation.claims.domain

data class ClaimAnalysisOccurrenceInput(
    val ordinal: Int,
    val markerText: String,
    val startOffset: Int,
    val endOffset: Int,
    val targets: List<ClaimAnalysisTargetCandidate>,
)
