package com.papertrail.api.citation.claims.domain

data class ClaimAnalysisContextInput(
    val contextText: String,
    val contextStartOffset: Int,
    val contextEndOffset: Int,
    val occurrences: List<ClaimAnalysisOccurrenceInput>,
) {
    val targetCandidates: List<ClaimAnalysisTargetCandidate> = occurrences.flatMap(ClaimAnalysisOccurrenceInput::targets)
}
