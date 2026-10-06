package com.papertrail.api.citation.parsing

data class ParsedScientificDocument(
    val parserId: String,
    val parserVersion: String,
    val normalizedSourceText: String,
    val sections: List<ParsedSection>,
    val citationContexts: List<ParsedCitationContext>,
    val bibliographyEntries: List<ParsedBibliographyEntry>,
    val rawParserOutput: ByteArray = ByteArray(0),
)
