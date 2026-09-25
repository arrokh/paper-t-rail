package com.papertrail.api.references

data class ReferenceResolutionSummary(
    val total: Int,
    val resolved: Int,
    val unresolved: Int,
    val unsupportedReferenceType: Int,
    val notAttempted: Int,
)
