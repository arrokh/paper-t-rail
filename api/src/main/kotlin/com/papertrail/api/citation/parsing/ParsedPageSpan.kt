package com.papertrail.api.citation.parsing

data class ParsedPageSpan(
    val startOffset: Int,
    val endOffset: Int,
    val pageNumber: Int,
) {
    init {
        require(startOffset >= 0 && endOffset > startOffset) { "Parsed page spans must have a non-empty source range." }
        require(pageNumber > 0) { "Parsed physical PDF page numbers are one-based." }
    }
}
