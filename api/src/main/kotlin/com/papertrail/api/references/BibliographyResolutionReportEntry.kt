package com.papertrail.api.references

data class BibliographyResolutionReportEntry(
    val entryOrder: Int,
    val localReferenceKey: String,
    val rawText: String,
    val title: String?,
    val authors: List<String>,
    val year: Int?,
    val doi: String?,
    val referenceType: String,
    val status: String,
    val reasonCode: String?,
    val canonicalPaper: ReportCanonicalPaper?,
    val confidenceScore: Double?,
    val matchMethod: String?,
)
