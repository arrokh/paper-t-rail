package com.papertrail.api.citation.parsing

/** A citation callout's span in normalized section text, with its GROBID bibliography keys. */
data class CitationMarker(
    val startOffset: Int,
    val endOffset: Int,
    val text: String,
    val referenceKeys: List<String>,
)
