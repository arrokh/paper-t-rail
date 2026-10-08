package com.papertrail.api.citation.parsing

import io.swagger.v3.oas.annotations.media.Schema

data class ParsedBibliographyIdentifier(
    @field:Schema(description = "GROBID TEI element that supplied this value, such as idno, ptr, or ref.")
    val sourceElement: String,
    @field:Schema(description = "Original GROBID identifier type, when present.")
    val type: String?,
    @field:Schema(description = "Original identifier text or TEI target value; no network lookup is implied.")
    val rawValue: String,
    @field:Schema(description = "Locally normalized value when a normalization is defined; otherwise null.")
    val normalizedValue: String?,
)
