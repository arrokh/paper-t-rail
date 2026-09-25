package com.papertrail.api.citation.claims.domain

import com.papertrail.api.citation.claims.domain.AtomicClaimCandidate

data class CitationContextClaims(
    val contextStartOffset: Int,
    val contextEndOffset: Int,
    val claims: List<AtomicClaimCandidate>,
)
