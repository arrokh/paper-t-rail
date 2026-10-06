package com.papertrail.api.citation.parsing

data class ParsedSection(
    val sectionOrder: Int,
    val heading: String?,
    val text: String,
    val startOffset: Int,
    val endOffset: Int,
)
