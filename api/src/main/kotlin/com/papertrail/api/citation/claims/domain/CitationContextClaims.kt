package com.papertrail.api.citation.claims.domain

data class CitationContextClaims(
    val contextStartOffset: Int,
    val contextEndOffset: Int,
    val claims: List<AnalyzedAtomicClaim>,
)
