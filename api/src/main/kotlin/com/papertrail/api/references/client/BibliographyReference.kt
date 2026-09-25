package com.papertrail.api.references.client

data class BibliographyReference(
    val title: String?,
    val authors: List<String>,
    val year: Int?,
    val doi: String?,
    val referenceType: String,
)
