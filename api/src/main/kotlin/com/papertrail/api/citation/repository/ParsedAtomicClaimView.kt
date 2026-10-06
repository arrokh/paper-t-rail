package com.papertrail.api.citation.repository

import java.util.UUID

data class ParsedAtomicClaimView(
    val id: UUID,
    val text: String,
    val sourceStartOffset: Int,
    val sourceEndOffset: Int,
    val citationTargets: List<ParsedClaimCitationTargetView>,
)
