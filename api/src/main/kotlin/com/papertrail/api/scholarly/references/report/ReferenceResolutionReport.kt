package com.papertrail.api.scholarly.references.report

data class ReferenceResolutionReport(
    val executionStatus: String,
    val scorePolicyVersion: String?,
    val confidenceThreshold: Double?,
    val summary: ReferenceResolutionSummary,
    val entries: List<BibliographyResolutionReportEntry>,
)
