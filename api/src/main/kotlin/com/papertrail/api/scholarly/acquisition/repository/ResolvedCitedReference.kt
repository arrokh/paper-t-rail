package com.papertrail.api.scholarly.acquisition.repository

import java.util.UUID

data class ResolvedCitedReference(
    val bibliographyEntryId: UUID,
    val title: String?,
    val authors: List<String>,
    val year: Int?,
    val doi: String?,
    val referenceType: String,
    val canonicalPaperId: UUID,
)
