package com.papertrail.api.citation.claims.provider

import com.papertrail.api.citation.claims.domain.AtomicClaimCandidate
import com.papertrail.api.citation.claims.domain.ClaimExtractionRequest

interface ClaimExtractorProvider {
    val providerId: String
    val version: String
    fun extract(request: ClaimExtractionRequest): List<AtomicClaimCandidate>
}
