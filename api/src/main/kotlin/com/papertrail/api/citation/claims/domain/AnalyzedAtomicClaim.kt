package com.papertrail.api.citation.claims.domain

data class AnalyzedAtomicClaim(
    val candidate: AtomicClaimCandidate,
    val citationTargetKeys: List<CitationTargetKey>,
)
