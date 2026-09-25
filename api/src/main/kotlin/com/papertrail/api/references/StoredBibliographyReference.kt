package com.papertrail.api.references

import java.util.UUID

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
