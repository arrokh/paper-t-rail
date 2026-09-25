package com.papertrail.api.citation.claims.domain

import com.papertrail.api.citation.parsing.ParsedCitationOccurrence

data class ClaimExtractionRequest(
    val contextText: String,
    val contextStartOffset: Int,
    val occurrences: List<ParsedCitationOccurrence>,
)
