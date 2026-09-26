package com.papertrail.api.evidence.report

data class EvidenceCoverageSummary(
    val totalVerifications: Int = 0,
    val completedVerifications: Int = 0,
    val incompleteVerifications: Int = 0,
    val evidenceConflicts: Int = 0,
    val supported: Int = 0,
    val partiallySupported: Int = 0,
    val contradicted: Int = 0,
    val insufficientEvidence: Int = 0,
    val inaccessible: Int = 0,
    val unresolved: Int = 0,
    val unsupportedReferenceType: Int = 0,
)
