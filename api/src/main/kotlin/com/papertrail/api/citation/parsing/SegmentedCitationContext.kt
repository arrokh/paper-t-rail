package com.papertrail.api.citation.parsing

data class SegmentedCitationContext(
    val boundaryKind: String,
    val startOffset: Int,
    val endOffset: Int,
    val text: String,
    val markers: List<CitationMarker>,
)
