package com.papertrail.api.citation.repository

import java.util.UUID

data class ParsedCitationOccurrenceView(val id: UUID, val markerText: String, val startOffset: Int, val endOffset: Int, val bibliographyReferenceKeys: List<String>)
