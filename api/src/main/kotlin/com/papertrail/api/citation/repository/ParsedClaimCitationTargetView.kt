package com.papertrail.api.citation.repository

import java.util.UUID

data class ParsedClaimCitationTargetView(
    val id: UUID,
    val markerText: String,
    val bibliographyReferenceKey: String,
    val bibliographyTitle: String?,
    val associationKind: String,
)
