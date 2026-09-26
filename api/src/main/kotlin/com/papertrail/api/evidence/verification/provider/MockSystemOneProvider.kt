package com.papertrail.api.evidence.verification.provider

import com.papertrail.api.evidence.verification.domain.EvidenceJudgement
import com.papertrail.api.evidence.verification.domain.EvidenceJudgementKind
import com.papertrail.api.evidence.verification.domain.EvidencePassageForJudgement
import com.papertrail.api.evidence.verification.domain.EvidenceRole
import com.papertrail.api.evidence.verification.domain.SemanticJudgementRequest
import com.papertrail.api.evidence.verification.domain.SemanticJudgementResult
import org.springframework.stereotype.Component

/**
 * Deterministic local provider. Only explicit fixture markers produce non-insufficient judgements;
 * unmarked passages are never presented as semantically assessed.
 */
@Component
class MockSystemOneProvider : SystemOneProvider {
    override val providerId = "mock"
    override val version = "v1"
    override val modelId = "mock-v1"

    override fun evaluate(request: SemanticJudgementRequest): SemanticJudgementResult = SemanticJudgementResult(
        evidenceJudgements = request.evidencePassages.map(::judgeFixturePassage),
    )

    private fun judgeFixturePassage(passage: EvidencePassageForJudgement): EvidenceJudgement {
        val (kind, score) = when {
            passage.text.contains(DIRECT_SUPPORT_FIXTURE_MARKER) -> EvidenceJudgementKind.DIRECT_SUPPORT to FIXTURE_STRENGTH
            passage.text.contains(PARTIAL_SUPPORT_FIXTURE_MARKER) -> EvidenceJudgementKind.PARTIAL_SUPPORT to FIXTURE_STRENGTH
            passage.text.contains(CONTRADICTION_FIXTURE_MARKER) -> EvidenceJudgementKind.CONTRADICTS to FIXTURE_STRENGTH
            passage.text.contains(UNRELATED_FIXTURE_MARKER) -> EvidenceJudgementKind.UNRELATED to FIXTURE_STRENGTH
            else -> EvidenceJudgementKind.INSUFFICIENT to 0.0
        }
        return EvidenceJudgement(
            evidenceCandidateId = passage.id,
            judgement = kind,
            evidenceRole = roleFor(passage.sectionHeading),
            confidence = score,
            directness = score,
            claimScopeMatch = score,
            studyDesignQuality = score,
            relevance = score,
        )
    }

    private fun roleFor(sectionHeading: String?): EvidenceRole =
        if (sectionHeading.orEmpty().contains(REVIEW_SECTION, ignoreCase = true)) {
            EvidenceRole.AUTHOR_SYNTHESIS
        } else {
            EvidenceRole.PRIMARY_FINDING
        }

    companion object {
        const val DIRECT_SUPPORT_FIXTURE_MARKER = "[[PT-SYSTEM-ONE:DIRECT_SUPPORT]]"
        const val PARTIAL_SUPPORT_FIXTURE_MARKER = "[[PT-SYSTEM-ONE:PARTIAL_SUPPORT]]"
        const val CONTRADICTION_FIXTURE_MARKER = "[[PT-SYSTEM-ONE:CONTRADICTS]]"
        const val UNRELATED_FIXTURE_MARKER = "[[PT-SYSTEM-ONE:UNRELATED]]"

        private const val REVIEW_SECTION = "review"
        private const val FIXTURE_STRENGTH = 0.96
    }
}
