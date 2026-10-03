package com.papertrail.api.citation.claims.domain

data class ClaimAnalysisRequest(
    val contexts: List<ClaimAnalysisContextInput>,
)
