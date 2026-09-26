package com.papertrail.api.evidence.report

data class EvidenceJudgementReport(
    val providerId: String,
    val modelId: String?,
    val providerVersion: String,
    val judgement: String,
    val evidenceRole: String,
    val confidence: Double,
    val directness: Double,
    val claimScopeMatch: Double,
    val studyDesignQuality: Double,
    val relevance: Double,
    val calibratedStrength: Double,
)
