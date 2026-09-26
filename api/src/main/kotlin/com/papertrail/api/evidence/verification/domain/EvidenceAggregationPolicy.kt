package com.papertrail.api.evidence.verification.domain

import com.papertrail.api.scholarly.acquisition.domain.TerminalVerificationStatus
import kotlin.math.abs

class EvidenceAggregationPolicy(
    private val thresholds: EvidenceAggregationThresholds,
) {
    fun aggregate(judgements: List<EvidenceJudgement>): EvidenceAggregationDecision {
        val directSupport = judgements
            .filter { it.judgement == EvidenceJudgementKind.DIRECT_SUPPORT }
            .maxOfOrNull(EvidenceJudgement::strength)
            ?.takeIf { it >= thresholds.directSupport }
        val partialSupport = judgements
            .filter { it.judgement == EvidenceJudgementKind.PARTIAL_SUPPORT }
            .maxOfOrNull(EvidenceJudgement::strength)
            ?.takeIf { it >= thresholds.partialSupport }
        val contradiction = judgements
            .filter { it.judgement == EvidenceJudgementKind.CONTRADICTS }
            .maxOfOrNull(EvidenceJudgement::strength)
            ?.takeIf { it >= thresholds.contradiction }
        val support = listOfNotNull(directSupport, partialSupport).maxOrNull()

        if (support != null && contradiction != null && abs(support - contradiction) <= thresholds.comparabilityMargin) {
            return EvidenceAggregationDecision(
                finalStatus = TerminalVerificationStatus.INSUFFICIENT_EVIDENCE,
                evidenceConflict = true,
                strongestSupport = support,
                strongestContradiction = contradiction,
            )
        }

        if (directSupport != null && (contradiction == null || directSupport > contradiction + thresholds.comparabilityMargin)) {
            return decision(TerminalVerificationStatus.SUPPORTED, support, contradiction)
        }

        if (contradiction != null && (support == null || contradiction > support + thresholds.comparabilityMargin)) {
            return decision(TerminalVerificationStatus.CONTRADICTED, support, contradiction)
        }

        if (partialSupport != null && (contradiction == null || partialSupport > contradiction + thresholds.comparabilityMargin)) {
            return decision(TerminalVerificationStatus.PARTIALLY_SUPPORTED, support, contradiction)
        }

        return decision(TerminalVerificationStatus.INSUFFICIENT_EVIDENCE, support, contradiction)
    }

    private fun decision(
        status: TerminalVerificationStatus,
        support: Double?,
        contradiction: Double?,
    ) = EvidenceAggregationDecision(status, false, support, contradiction)

    companion object {
        const val POLICY_VERSION = "conflict-aware-evidence-strength-v1"
    }
}
