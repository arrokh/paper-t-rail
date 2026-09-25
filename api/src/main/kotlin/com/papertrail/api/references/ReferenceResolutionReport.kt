package com.papertrail.api.references

data class ReferenceResolutionReport(
    val executionStatus: String,
    val scorePolicyVersion: String?,
    val confidenceThreshold: Double?,
    val summary: ReferenceResolutionSummary,
    val entries: List<BibliographyResolutionReportEntry>,
)
