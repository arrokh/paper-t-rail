package com.papertrail.api.calibration

import com.papertrail.api.evidence.verification.domain.EvidenceAggregationPolicy
import com.papertrail.api.evidence.verification.domain.EvidenceAggregationThresholds
import com.papertrail.api.evidence.verification.domain.EvidenceJudgement
import com.papertrail.api.evidence.verification.domain.EvidenceJudgementKind
import com.papertrail.api.evidence.verification.domain.EvidenceRole
import com.papertrail.api.scholarly.references.client.BibliographyReference
import com.papertrail.api.scholarly.references.resolver.ScholarlyMetadataMatcher
import java.time.Instant

/** Versioned, reviewable inputs for the offline reference and evidence-policy benchmark. */
data class CalibrationFixture(
    val schemaVersion: Int,
    val fixtureId: String,
    val fixtureStatus: LabelState,
    val releaseCalibrationStatus: String,
    val provenance: Provenance,
    val referencePolicyCandidates: List<ReferencePolicyCandidate>,
    val aggregationPolicyCandidates: List<AggregationPolicyCandidate>,
    val referenceCases: List<ReferenceCase>,
    val evidenceCases: List<EvidenceCase>,
) {
    fun validate() {
        require(schemaVersion == CURRENT_SCHEMA_VERSION) { "Unsupported calibration fixture schemaVersion '$schemaVersion'." }
        require(fixtureId.isNotBlank()) { "Calibration fixture fixtureId must not be blank." }
        require(releaseCalibrationStatus == NOT_APPROVED) {
            "A benchmark fixture cannot approve calibration; its status is independent of exploratory benchmark metrics."
        }
        provenance.validate("fixture")
        require(referenceCases.isNotEmpty()) { "Calibration fixture must contain reference cases." }
        require(evidenceCases.isNotEmpty()) { "Calibration fixture must contain evidence cases." }
        require(referencePolicyCandidates.isNotEmpty()) { "Calibration fixture must contain reference policy candidates." }
        require(aggregationPolicyCandidates.isNotEmpty()) { "Calibration fixture must contain aggregation policy candidates." }

        val allCaseIds = referenceCases.map(ReferenceCase::caseId) + evidenceCases.map(EvidenceCase::caseId)
        require(allCaseIds.all(String::isNotBlank) && allCaseIds.distinct().size == allCaseIds.size) {
            "Calibration case IDs must be non-blank and unique across both case types."
        }
        referenceCases.forEach(ReferenceCase::validate)
        evidenceCases.forEach(EvidenceCase::validate)
        referencePolicyCandidates.forEach(ReferencePolicyCandidate::validate)
        aggregationPolicyCandidates.forEach(AggregationPolicyCandidate::validate)
        require(referencePolicyCandidates.map(ReferencePolicyCandidate::candidateId).distinct().size == referencePolicyCandidates.size) {
            "Reference policy candidate IDs must be unique."
        }
        require(aggregationPolicyCandidates.map(AggregationPolicyCandidate::candidateId).distinct().size == aggregationPolicyCandidates.size) {
            "Aggregation policy candidate IDs must be unique."
        }
        require(referenceCases.any { it.expected.status == ResolutionOutcome.RESOLVED }) {
            "Calibration fixture must include a confirmed reference match."
        }
        require(referenceCases.any { it.category == ReferenceCategory.NEAR_MISS && it.expected.status == ResolutionOutcome.UNRESOLVED }) {
            "Calibration fixture must include an unresolved near-miss reference decoy."
        }
        val evidenceCategories = evidenceCases.map(EvidenceCase::category).toSet()
        require(evidenceCategories.containsAll(REQUIRED_EVIDENCE_CATEGORIES)) {
            "Calibration fixture is missing one or more required evidence categories."
        }
        if (fixtureStatus == LabelState.HUMAN_REVIEWED) {
            require((referenceCases.map(ReferenceCase::adjudication) + evidenceCases.map(EvidenceCase::adjudication))
                .all { it.state == LabelState.HUMAN_REVIEWED }) {
                "A HUMAN_REVIEWED fixture must have human-reviewed labels for every case."
            }
        }
    }

    data class Provenance(
        val kind: ProvenanceKind,
        val recordId: String,
        val description: String,
        val citation: String? = null,
    ) {
        fun validate(owner: String) {
            require(recordId.isNotBlank() && description.isNotBlank()) {
                "Calibration provenance for $owner must include recordId and description."
            }
            require(citation == null || citation.isNotBlank()) { "Calibration provenance citation must not be blank." }
            require(kind != ProvenanceKind.PUBLISHED || !citation.isNullOrBlank()) {
                "Published-source provenance for $owner must include a citation or stable source URL."
            }
        }
    }

    data class Adjudication(
        val state: LabelState,
        val reviewer: String? = null,
        val reviewedAt: String? = null,
        val rationale: String? = null,
    ) {
        fun validate(caseId: String) {
            if (state == LabelState.DRAFT) {
                require(reviewer == null && reviewedAt == null && rationale == null) {
                    "Draft labels for '$caseId' must not carry human-review metadata."
                }
                return
            }
            require(!reviewer.isNullOrBlank() && !reviewedAt.isNullOrBlank() && !rationale.isNullOrBlank()) {
                "Human-reviewed labels for '$caseId' require reviewer, reviewedAt, and rationale."
            }
            try {
                Instant.parse(reviewedAt)
            } catch (_: Exception) {
                throw IllegalArgumentException("Human-reviewed label timestamp for '$caseId' must be an ISO-8601 instant.")
            }
        }
    }

    data class ReferencePolicyCandidate(
        val candidateId: String,
        val scorePolicyVersion: String,
        val confidenceThreshold: Double,
        val ambiguityMargin: Double,
    ) {
        fun validate() {
            require(candidateId.isNotBlank()) { "Reference policy candidate ID must not be blank." }
            require(scorePolicyVersion == ScholarlyMetadataMatcher.POLICY_VERSION) {
                "Unsupported reference score policy '$scorePolicyVersion'."
            }
            require(confidenceThreshold in 0.0..1.0 && ambiguityMargin in 0.0..1.0) {
                "Reference candidate thresholds must be between zero and one."
            }
        }
    }

    data class AggregationPolicyCandidate(
        val candidateId: String,
        val verificationPolicyVersion: String,
        val aggregationPolicyVersion: String,
        val thresholds: EvidenceAggregationThresholds,
    ) {
        fun validate() {
            require(candidateId.isNotBlank()) { "Aggregation policy candidate ID must not be blank." }
            require(verificationPolicyVersion == EvidenceJudgement.STRENGTH_RUBRIC_VERSION) {
                "Unsupported evidence-strength rubric '$verificationPolicyVersion'."
            }
            require(aggregationPolicyVersion == EvidenceAggregationPolicy.POLICY_VERSION) {
                "Unsupported evidence aggregation policy '$aggregationPolicyVersion'."
            }
        }
    }

    data class ReferenceCase(
        val caseId: String,
        val category: ReferenceCategory,
        val provenance: Provenance,
        val adjudication: Adjudication,
        val reference: BibliographyReference,
        val candidates: List<ReferenceCandidate>,
        val expected: ReferenceExpectation,
    ) {
        fun validate() {
            require(caseId.isNotBlank()) { "Reference case ID must not be blank." }
            provenance.validate(caseId)
            adjudication.validate(caseId)
            require(reference.referenceType.isNotBlank()) { "Reference case '$caseId' must include a reference type." }
            require(candidates.isNotEmpty()) { "Reference case '$caseId' must include at least one candidate." }
            require(candidates.all { it.title.isNotBlank() }) { "Reference candidates in '$caseId' must include titles." }
            require(candidates.map(ReferenceCandidate::candidateId).all(String::isNotBlank) &&
                candidates.map(ReferenceCandidate::candidateId).distinct().size == candidates.size) {
                "Reference candidate IDs in '$caseId' must be non-blank and unique."
            }
            require((expected.status == ResolutionOutcome.RESOLVED) == (expected.candidateId != null)) {
                "Reference expectation for '$caseId' must provide a candidate ID exactly when status is RESOLVED."
            }
            require(expected.candidateId == null || candidates.any { it.candidateId == expected.candidateId }) {
                "Expected candidate for '$caseId' is not present in its candidate list."
            }
            when (category) {
                ReferenceCategory.CONFIRMED_MATCH -> require(expected.status == ResolutionOutcome.RESOLVED)
                ReferenceCategory.NEAR_MISS, ReferenceCategory.AMBIGUOUS_MATCH -> require(expected.status == ResolutionOutcome.UNRESOLVED)
            }
        }
    }

    data class ReferenceCandidate(
        val candidateId: String,
        val doi: String?,
        val title: String,
        val authors: List<String>,
        val year: Int?,
    )

    data class ReferenceExpectation(
        val status: ResolutionOutcome,
        val candidateId: String? = null,
    )

    data class EvidenceCase(
        val caseId: String,
        val category: EvidenceCategory,
        val provenance: Provenance,
        val adjudication: Adjudication,
        val judgements: List<EvidenceJudgementInput>,
        val expectedStatus: String,
        val expectedConflict: Boolean,
    ) {
        fun validate() {
            require(caseId.isNotBlank()) { "Evidence case ID must not be blank." }
            provenance.validate(caseId)
            adjudication.validate(caseId)
            require(judgements.isNotEmpty()) { "Evidence case '$caseId' must include at least one judgement." }
            require(expectedStatus in ALLOWED_AGGREGATION_STATUSES) {
                "Evidence case '$caseId' has unsupported expected aggregation status '$expectedStatus'."
            }
            judgements.forEach(EvidenceJudgementInput::validate)
            when (category) {
                EvidenceCategory.DIRECT_SUPPORT -> require(expectedStatus == "SUPPORTED" && !expectedConflict)
                EvidenceCategory.PARTIAL_SUPPORT -> require(expectedStatus == "PARTIALLY_SUPPORTED" && !expectedConflict)
                EvidenceCategory.CONTRADICTION -> require(expectedStatus == "CONTRADICTED" && !expectedConflict)
                EvidenceCategory.COMPARABLE_CONFLICT -> require(expectedStatus == "INSUFFICIENT_EVIDENCE" && expectedConflict)
                EvidenceCategory.CONFIDENCE_WITHOUT_SCOPE -> require(expectedStatus == "INSUFFICIENT_EVIDENCE" && !expectedConflict)
                EvidenceCategory.SECONDARY_REPORT_ONLY -> require(expectedStatus == "INSUFFICIENT_EVIDENCE" && !expectedConflict)
            }
        }
    }

    data class EvidenceJudgementInput(
        val judgement: EvidenceJudgementKind,
        val evidenceRole: EvidenceRole,
        val confidence: Double,
        val directness: Double,
        val claimScopeMatch: Double,
        val studyDesignQuality: Double,
        val relevance: Double,
    ) {
        fun validate() {
            require(listOf(confidence, directness, claimScopeMatch, studyDesignQuality, relevance).all { it in 0.0..1.0 }) {
                "Calibration fixture evidence scores must be between zero and one."
            }
        }

        fun toJudgement(candidateId: java.util.UUID) = EvidenceJudgement(
            evidenceCandidateId = candidateId,
            judgement = judgement,
            evidenceRole = evidenceRole,
            confidence = confidence,
            directness = directness,
            claimScopeMatch = claimScopeMatch,
            studyDesignQuality = studyDesignQuality,
            relevance = relevance,
        )
    }

    enum class LabelState { DRAFT, HUMAN_REVIEWED }
    enum class ProvenanceKind { SYNTHETIC, PUBLISHED }
    enum class ResolutionOutcome { RESOLVED, UNRESOLVED }
    enum class ReferenceCategory { CONFIRMED_MATCH, NEAR_MISS, AMBIGUOUS_MATCH }
    enum class EvidenceCategory {
        DIRECT_SUPPORT,
        PARTIAL_SUPPORT,
        CONTRADICTION,
        COMPARABLE_CONFLICT,
        CONFIDENCE_WITHOUT_SCOPE,
        SECONDARY_REPORT_ONLY,
    }

    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
        const val NOT_APPROVED = "NOT_APPROVED"
        val REQUIRED_EVIDENCE_CATEGORIES = setOf(
            EvidenceCategory.DIRECT_SUPPORT,
            EvidenceCategory.PARTIAL_SUPPORT,
            EvidenceCategory.CONTRADICTION,
            EvidenceCategory.COMPARABLE_CONFLICT,
            EvidenceCategory.CONFIDENCE_WITHOUT_SCOPE,
        )
        private val ALLOWED_AGGREGATION_STATUSES = setOf(
            "SUPPORTED",
            "PARTIALLY_SUPPORTED",
            "CONTRADICTED",
            "INSUFFICIENT_EVIDENCE",
        )
    }
}
