package com.papertrail.api.calibration

import com.papertrail.api.evidence.verification.domain.EvidenceAggregationThresholds
import com.papertrail.api.evidence.verification.domain.EvidenceJudgementKind
import com.papertrail.api.evidence.verification.domain.EvidenceRole
import com.papertrail.api.evidence.verification.provider.LayaSystemOneSettings
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LayaEvaluationPlanTest {
    @Test
    fun `held-out fingerprint is stable under input ordering and changes when held-out content changes`() {
        val dataset = draftDataset()
        val fingerprint = dataset.heldOutSplitSha256()
        val reordered = dataset.copy(
            papers = dataset.papers.reversed(),
            cases = dataset.cases.reversed(),
            claimPaperOutcomes = dataset.claimPaperOutcomes.reversed(),
            aggregationCandidates = dataset.aggregationCandidates.reversed(),
        )
        val changedHeldOut = dataset.copy(
            cases = dataset.cases.map { case ->
                if (case.split == LayaEvaluationDataset.Split.HELD_OUT) {
                    case.copy(evidencePassage = "Changed held-out test-only passage.")
                } else {
                    case
                }
            },
        )
        val changedCalibration = dataset.copy(
            cases = dataset.cases.map { case ->
                if (case.split == LayaEvaluationDataset.Split.CALIBRATION) {
                    case.copy(evidencePassage = "Changed calibration test-only passage.")
                } else {
                    case
                }
            },
        )

        assertEquals(fingerprint, reordered.heldOutSplitSha256())
        assertNotEquals(fingerprint, changedHeldOut.heldOutSplitSha256())
        assertEquals(fingerprint, changedCalibration.heldOutSplitSha256())
    }

    @Test
    fun `pre-registration cannot authorize synthetic draft data for held-out evaluation`() {
        val dataset = draftDataset()
        val datasetSha256 = "d".repeat(64)
        val plan = LayaEvaluationPlan(
            schemaVersion = LayaEvaluationPlan.CURRENT_SCHEMA_VERSION,
            planId = "plan-test-v1",
            datasetId = dataset.datasetId,
            datasetVersion = dataset.datasetVersion,
            datasetSha256 = datasetSha256,
            heldOutSplitSha256 = dataset.heldOutSplitSha256(),
            candidate = dataset.candidate,
            approvedBy = "test-reviewer",
            approvedAt = "2026-09-28T11:00:00Z",
            applicationRevision = "a".repeat(40),
            heldOutCitedPaperIds = setOf("paper-test"),
            uncertaintyMethodId = LayaEvaluationDataset.UNCERTAINTY_METHOD_ID,
            safetyBar = listOf(
                LayaEvaluationPlan.SafetyBarCriterion(
                    metricId = "judgement.accuracy",
                    comparison = LayaEvaluationPlan.Comparison.AT_LEAST,
                    threshold = 0.9,
                    rationale = "Test-only plan.",
                ),
            ),
        )

        val error = assertThrows(IllegalArgumentException::class.java) {
            plan.validateAgainst(dataset, datasetSha256)
        }

        assertTrue(error.message.orEmpty().contains("HUMAN_REVIEWED"))
        assertEquals(LayaEvaluationDataset.DatasetStatus.DRAFT, dataset.status)
    }

    private fun draftDataset(): LayaEvaluationDataset {
        val cases = listOf(
            evaluationCase("calibration-case", "paper-cal", "claim-paper-cal", LayaEvaluationDataset.Split.CALIBRATION),
            evaluationCase("held-out-case", "paper-test", "claim-paper-test", LayaEvaluationDataset.Split.HELD_OUT),
        )
        return LayaEvaluationDataset(
            schemaVersion = LayaEvaluationDataset.CURRENT_SCHEMA_VERSION,
            datasetId = "draft-plan-test",
            datasetVersion = "0.1.0-draft",
            status = LayaEvaluationDataset.DatasetStatus.DRAFT,
            protocolId = "laya-human-calibration-v1-draft",
            candidate = LayaEvaluationDataset.Candidate(
                checkpoint = LayaSystemOneSettings.PINNED_MODEL_ID,
                runtime = LayaSystemOneSettings.PINNED_RUNTIME_VERSION,
                outputMapping = LayaSystemOneSettings.OUTPUT_MAPPING_VERSION,
            ),
            papers = listOf(paper("paper-cal"), paper("paper-test")),
            cases = cases,
            claimPaperOutcomes = listOf(
                outcome("claim-paper-cal", "paper-cal", LayaEvaluationDataset.Split.CALIBRATION),
                outcome("claim-paper-test", "paper-test", LayaEvaluationDataset.Split.HELD_OUT),
            ),
            aggregationCandidates = listOf(
                LayaEvaluationDataset.AggregationCandidate(
                    candidateId = "design-baseline",
                    verificationPolicyVersion = "weighted-evidence-role-scope-design-v1",
                    aggregationPolicyVersion = "conflict-aware-evidence-strength-v1",
                    thresholds = EvidenceAggregationThresholds(0.80, 0.70, 0.80, 0.08),
                ),
            ),
        )
    }

    private fun paper(id: String) = LayaEvaluationDataset.PaperProvenance(
        citedPaperId = id,
        citation = "Synthetic test citation $id",
        sourceUrl = "https://example.test/$id",
        licenseOrRightsBasis = "Synthetic test-only fixture.",
        assetSha256 = "a".repeat(64),
    )

    private fun evaluationCase(
        caseId: String,
        paperId: String,
        claimPaperId: String,
        split: LayaEvaluationDataset.Split,
    ) = LayaEvaluationDataset.Case(
        caseId = caseId,
        citedPaperId = paperId,
        claimPaperId = claimPaperId,
        split = split,
        sourceLocatorId = "synthetic-page-section-locator",
        atomicClaim = "Synthetic test claim.",
        evidencePassage = "Synthetic test passage.",
        sectionHeading = "Results",
        adjudicatedLabels = LayaEvaluationDataset.HumanLabels(
            judgement = EvidenceJudgementKind.DIRECT_SUPPORT,
            evidenceRole = EvidenceRole.PRIMARY_FINDING,
            directness = 3,
            claimScopeMatch = 3,
            studyDesignQuality = 3,
            relevance = 3,
        ),
    )

    private fun outcome(
        claimPaperId: String,
        paperId: String,
        split: LayaEvaluationDataset.Split,
    ) = LayaEvaluationDataset.ClaimPaperOutcome(
        claimPaperId = claimPaperId,
        citedPaperId = paperId,
        split = split,
        expectedStatus = LayaEvaluationDataset.ClaimPaperStatus.SUPPORTED,
        expectedConflict = false,
    )
}
