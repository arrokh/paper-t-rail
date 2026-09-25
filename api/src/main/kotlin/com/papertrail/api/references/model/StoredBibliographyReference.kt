package com.papertrail.api.references.model

import java.util.UUID

/** Read projection of one persisted `bibliography_entries` row used during resolution. */
data class StoredBibliographyReference(
    val id: UUID,
    val entryOrder: Int,
    val localReferenceKey: String,
    val rawText: String,
    val title: String?,
    val authors: List<String>,
    val year: Int?,
    val doi: String?,
    val referenceType: String,
)
