package com.papertrail.api.citation.parsing

data class ParsedCitationContext(
    val sectionOrder: Int,
    val boundaryKind: String,
    val text: String,
    val startOffset: Int,
    val endOffset: Int,
    val occurrences: List<ParsedCitationOccurrence>,
)
