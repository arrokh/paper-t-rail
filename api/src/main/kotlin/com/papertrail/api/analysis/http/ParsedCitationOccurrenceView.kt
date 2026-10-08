package com.papertrail.api.analysis.http

import io.swagger.v3.oas.annotations.media.Schema
import java.util.UUID

data class ParsedCitationOccurrenceView(
    val id: UUID,
    val markerText: String,
    val startOffset: Int,
    val endOffset: Int,
    val bibliographyReferenceKeys: List<String>,
    @field:Schema(description = "TEI target keys that did not map to a source xml:id. Null for older parses that did not retain unmatched targets.")
    val unmatchedBibliographyReferenceKeys: List<String>? = null,
)
