package com.papertrail.api.scholarly.references.report

data class ReferenceResolutionSummary(
    val total: Int,
    val resolved: Int,
    val unresolved: Int,
    val unsupportedReferenceType: Int,
    val notAttempted: Int,
    val failed: Int,
)
