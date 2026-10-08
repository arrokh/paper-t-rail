package com.papertrail.api.citation.parsing

enum class ParsedBibliographicMetadataExtractionMethod {
    DOCLING_LABEL,
    FIRST_PAGE_SECTION_HEADER,
    FIRST_TEXT_AFTER_TITLE_HEADING,
    EXPLICIT_DOI_PREFIX,
}
