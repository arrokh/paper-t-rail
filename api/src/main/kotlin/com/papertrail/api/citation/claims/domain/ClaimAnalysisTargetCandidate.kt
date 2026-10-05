package com.papertrail.api.citation.claims.domain

/** Minimum GROBID-parsed bibliography data shown as a candidate for one Citation Target. */
data class ClaimAnalysisTargetCandidate(
    val key: CitationTargetKey,
    val title: String?,
    val authors: List<String>,
    val year: Int?,
    val doi: String?,
)
