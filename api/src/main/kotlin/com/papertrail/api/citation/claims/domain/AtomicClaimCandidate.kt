package com.papertrail.api.citation.claims.domain

/** A source location is an absolute, zero-based, end-exclusive UTF-16 span. */
data class AtomicClaimCandidate(
    val text: String,
    val sourceStartOffset: Int,
    val sourceEndOffset: Int,
)
