package com.papertrail.api.analysis.http

import java.util.UUID

data class ParsedClaimCitationTargetView(
    val id: UUID,
    val markerText: String,
    val bibliographyReferenceKey: String,
    val bibliographyTitle: String?,
    val associationKind: String,
)
