package com.papertrail.api.citation.parsing

import io.swagger.v3.oas.annotations.media.Schema

data class ParsedBibliographySourceLocation(
    @field:Schema(description = "Page number reported by GROBID, or null when coordinates do not contain a parseable page number.")
    val page: Int?,
    @field:Schema(description = "Original GROBID coords value. It is retained as metadata and is not a normalized source-text span.")
    val coordinates: String,
)
