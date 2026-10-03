package com.papertrail.api.evidence.verification.domain

import java.util.UUID

data class EvidenceJudgement(
    val evidenceCandidateId: UUID,
    val judgement: EvidenceJudgementKind,
    val evidenceRole: EvidenceRole,
    val confidence: Double,
    val directness: Double,
    val claimScopeMatch: Double,
    val studyDesignQuality: Double,
    val relevance: Double,
    val providerReportedModelId: String? = null,
) {
    init {
        require(providerReportedModelId == null ||
            (providerReportedModelId.isNotBlank() && providerReportedModelId.length <= 160 && providerReportedModelId.none(Char::isISOControl))
        ) { "Provider-reported model identifiers must be non-empty, bounded, and free of control characters." }
        require(listOf(confidence, directness, claimScopeMatch, studyDesignQuality, relevance).all { it in 0.0..1.0 }) {
            "Evidence judgement scores must be between zero and one."
        }
    }

    fun strength(): Double = evidenceRole.strengthMultiplier * (
        confidence * CONFIDENCE_WEIGHT +
            directness * DIRECTNESS_WEIGHT +
            claimScopeMatch * CLAIM_SCOPE_WEIGHT +
            studyDesignQuality * STUDY_DESIGN_WEIGHT +
            relevance * RELEVANCE_WEIGHT
        )

    fun rawScores(): Map<String, Double> = mapOf(
        "confidence" to confidence,
        "directness" to directness,
        "claimScopeMatch" to claimScopeMatch,
        "studyDesignQuality" to studyDesignQuality,
        "relevance" to relevance,
        "roleMultiplier" to evidenceRole.strengthMultiplier,
        "calibratedStrength" to strength(),
    )

    companion object {
        const val STRENGTH_RUBRIC_VERSION = "weighted-evidence-role-scope-design-v1"

        private const val CONFIDENCE_WEIGHT = 0.20
        private const val DIRECTNESS_WEIGHT = 0.25
        private const val CLAIM_SCOPE_WEIGHT = 0.25
        private const val STUDY_DESIGN_WEIGHT = 0.15
        private const val RELEVANCE_WEIGHT = 0.15
    }
}
