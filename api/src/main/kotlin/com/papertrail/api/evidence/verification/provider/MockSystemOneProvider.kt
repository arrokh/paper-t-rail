package com.papertrail.api.evidence.verification.provider

import com.papertrail.api.evidence.verification.domain.EvidenceJudgement
import com.papertrail.api.evidence.verification.domain.EvidenceJudgementKind
import com.papertrail.api.evidence.verification.domain.EvidenceRole
import com.papertrail.api.evidence.verification.domain.SemanticJudgementRequest
import com.papertrail.api.evidence.verification.domain.SemanticJudgementResult
import org.springframework.stereotype.Component

@Component
class MockSystemOneProvider : SystemOneProvider {
    override val providerId = "mock"
    override val version = "v1"
    override val modelId = "mock-v1"

    override fun evaluate(request: SemanticJudgementRequest): SemanticJudgementResult = SemanticJudgementResult(
        evidenceJudgements = request.evidencePassages.map { passage ->
            EvidenceJudgement(
                evidenceCandidateId = passage.id,
                judgement = EvidenceJudgementKind.INSUFFICIENT,
                evidenceRole = roleFor(passage.sectionHeading),
                confidence = 0.0,
                directness = 0.0,
                claimScopeMatch = 0.0,
                studyDesignQuality = 0.0,
                relevance = 0.0,
            )
        },
    )

    private fun roleFor(sectionHeading: String?): EvidenceRole =
        if (sectionHeading.orEmpty().contains(REVIEW_SECTION, ignoreCase = true)) {
            EvidenceRole.AUTHOR_SYNTHESIS
        } else {
            EvidenceRole.PRIMARY_FINDING
        }

    companion object {
        private const val REVIEW_SECTION = "review"
    }
}
