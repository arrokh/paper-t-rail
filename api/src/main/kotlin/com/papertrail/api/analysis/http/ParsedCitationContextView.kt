package com.papertrail.api.analysis.http

import java.util.UUID

data class ParsedCitationContextView(
    val id: UUID,
    val sectionId: UUID,
    val boundaryKind: String,
    val text: String,
    val startOffset: Int,
    val endOffset: Int,
    val occurrences: List<ParsedCitationOccurrenceView>,
    val atomicClaims: List<ParsedAtomicClaimView> = emptyList(),
)
