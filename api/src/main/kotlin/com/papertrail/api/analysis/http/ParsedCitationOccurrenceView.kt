package com.papertrail.api.analysis.http

import java.util.UUID

data class ParsedCitationOccurrenceView(val id: UUID, val markerText: String, val startOffset: Int, val endOffset: Int, val bibliographyReferenceKeys: List<String>)
