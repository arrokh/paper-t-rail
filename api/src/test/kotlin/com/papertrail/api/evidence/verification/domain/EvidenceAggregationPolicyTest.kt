package com.papertrail.api.evidence.verification.domain

import com.papertrail.api.scholarly.acquisition.domain.TerminalVerificationStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class EvidenceAggregationPolicyTest {
    private val policy = EvidenceAggregationPolicy(EvidenceAggregationThresholds.CALIBRATED_V1)

    @Test
    fun `terminal domain vocabulary remains exactly seven statuses`() {
        assertEquals(
            setOf(
                TerminalVerificationStatus.SUPPORTED,
                TerminalVerificationStatus.PARTIALLY_SUPPORTED,
                TerminalVerificationStatus.CONTRADICTED,
                TerminalVerificationStatus.INSUFFICIENT_EVIDENCE,
                TerminalVerificationStatus.INACCESSIBLE,
                TerminalVerificationStatus.UNRESOLVED,
                TerminalVerificationStatus.UNSUPPORTED_REFERENCE_TYPE,
            ),
            TerminalVerificationStatus.entries.toSet(),
        )
    }

    @Test
    fun `human-labeled calibration fixture preserves conservative outcomes`() {
        val fixture = listOf(
            CalibrationCase(
                "balanced credible support and contradiction",
                listOf(judgement(EvidenceJudgementKind.DIRECT_SUPPORT, 0.90), judgement(EvidenceJudgementKind.CONTRADICTS, 0.88)),
                TerminalVerificationStatus.INSUFFICIENT_EVIDENCE,
                conflict = true,
            ),
            CalibrationCase(
                "clearly stronger direct support",
                listOf(judgement(EvidenceJudgementKind.DIRECT_SUPPORT, 0.96), judgement(EvidenceJudgementKind.CONTRADICTS, 0.80)),
                TerminalVerificationStatus.SUPPORTED,
            ),
            CalibrationCase(
                "partial support without stronger contradiction",
                listOf(judgement(EvidenceJudgementKind.PARTIAL_SUPPORT, 0.80)),
                TerminalVerificationStatus.PARTIALLY_SUPPORTED,
            ),
            CalibrationCase(
                "clearly stronger contradiction",
                listOf(judgement(EvidenceJudgementKind.DIRECT_SUPPORT, 0.72), judgement(EvidenceJudgementKind.CONTRADICTS, 0.94)),
                TerminalVerificationStatus.CONTRADICTED,
            ),
            CalibrationCase(
                "high confidence without directness or scope match",
                listOf(judgement(EvidenceJudgementKind.DIRECT_SUPPORT, 0.0, confidence = 1.0, directness = 0.1, scope = 0.1, design = 0.1, relevance = 0.1)),
                TerminalVerificationStatus.INSUFFICIENT_EVIDENCE,
            ),
            CalibrationCase(
                "secondary report alone does not outweigh its provenance role",
                listOf(judgement(EvidenceJudgementKind.DIRECT_SUPPORT, 1.0, role = EvidenceRole.SECONDARY_REPORT)),
                TerminalVerificationStatus.INSUFFICIENT_EVIDENCE,
            ),
        )

        fixture.forEach { case ->
            val actual = policy.aggregate(case.judgements)
            assertEquals(case.expected, actual.finalStatus, case.name)
            assertEquals(case.conflict, actual.evidenceConflict, case.name)
        }
    }

    @Test
    fun `no qualifying passage is insufficient rather than a fabricated result`() {
        val result = policy.aggregate(listOf(judgement(EvidenceJudgementKind.UNRELATED, 1.0)))

        assertEquals(TerminalVerificationStatus.INSUFFICIENT_EVIDENCE, result.finalStatus)
        assertFalse(result.evidenceConflict)
        assertNullStrengths(result)
    }

    @Test
    fun `invalid provider scores are rejected before aggregation`() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException::class.java) {
            judgement(EvidenceJudgementKind.DIRECT_SUPPORT, 1.0, confidence = 1.1)
        }
    }

    private fun assertNullStrengths(result: EvidenceAggregationDecision) {
        assertEquals(null, result.strongestSupport)
        assertEquals(null, result.strongestContradiction)
    }

    private fun judgement(
        kind: EvidenceJudgementKind,
        score: Double,
        role: EvidenceRole = EvidenceRole.PRIMARY_FINDING,
        confidence: Double = score,
        directness: Double = score,
        scope: Double = score,
        design: Double = score,
        relevance: Double = score,
    ) = EvidenceJudgement(
        evidenceCandidateId = UUID.randomUUID(),
        judgement = kind,
        evidenceRole = role,
        confidence = confidence,
        directness = directness,
        claimScopeMatch = scope,
        studyDesignQuality = design,
        relevance = relevance,
    )

    private data class CalibrationCase(
        val name: String,
        val judgements: List<EvidenceJudgement>,
        val expected: TerminalVerificationStatus,
        val conflict: Boolean = false,
    )
}
