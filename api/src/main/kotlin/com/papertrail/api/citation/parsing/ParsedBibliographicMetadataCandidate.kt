package com.papertrail.api.citation.parsing

/** Extracted text with its classification rule; it is not an identity decision. */
data class ParsedBibliographicMetadataCandidate(
    val field: ParsedBibliographicMetadataField,
    val value: String,
    val pageNumber: Int,
    val sourceLabel: String,
    val extractionMethod: ParsedBibliographicMetadataExtractionMethod,
    val sourceElementId: String? = null,
    val sourceCharSpanStart: Int? = null,
    val sourceCharSpanEnd: Int? = null,
)
