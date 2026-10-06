package com.papertrail.api.citation.repository

import java.util.UUID

data class ParsedDocumentView(
    val parser: ParsedParserProvenance,
    val sourceContentSha256: String,
    val normalizedSourceText: String,
    val sections: List<ParsedSectionView>,
    val citationContexts: List<ParsedCitationContextView>,
    val bibliographyEntries: List<ParsedBibliographyEntryView>,
)
