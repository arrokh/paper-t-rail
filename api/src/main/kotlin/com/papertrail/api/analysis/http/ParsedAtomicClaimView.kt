package com.papertrail.api.analysis.http

import java.util.UUID

data class ParsedAtomicClaimView(
    val id: UUID,
    val text: String,
    val sourceStartOffset: Int,
    val sourceEndOffset: Int,
    val citationTargets: List<ParsedClaimCitationTargetView>,
)
