package com.papertrail.api.citation.parsing

data class ParsedCitationOccurrence(
    val markerText: String,
    val startOffset: Int,
    val endOffset: Int,
    val bibliographyReferenceKeys: List<String>,
    val unmatchedBibliographyReferenceKeys: List<String>? = null,
)
