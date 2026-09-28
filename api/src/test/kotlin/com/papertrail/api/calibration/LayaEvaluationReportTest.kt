package com.papertrail.api.calibration

import com.papertrail.api.evidence.verification.domain.EvidenceAggregationThresholds
import com.papertrail.api.evidence.verification.domain.EvidenceJudgementKind
import com.papertrail.api.evidence.verification.domain.EvidenceRole
import com.papertrail.api.evidence.verification.provider.LayaSystemOneProviderException
import com.papertrail.api.evidence.verification.provider.LayaSystemOneSettings
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Base64

class LayaEvaluationReportTest {
    @Test
    fun `reports calibration quality incomplete work and aggregation without input text`() {
        val dataset = dataset()
        val run = LayaEvaluationRun(
            schemaVersion = LayaEvaluationRun.CURRENT_SCHEMA_VERSION,
            datasetId = dataset.datasetId,
            datasetSha256 = "f".repeat(64),
            split = LayaEvaluationDataset.Split.CALIBRATION,
            candidate = dataset.candidate,
            applicationRevision = "a".repeat(40),
            evaluatedAt = "2026-09-28T12:00:00Z",
            caseResults = listOf(
                result("case-a", "paper-a", "group-a", Prediction(DIRECT_SUPPORT, PRIMARY_FINDING, 0.8, 0.875, 1.0, 0.75, 1.0)),
                result("case-b", "paper-b", "group-b", Prediction(DIRECT_SUPPORT, PRIMARY_FINDING, 0.7, 0.5, 0.5, 0.5, 0.5)),
                LayaEvaluationRun.CaseResult(
                    caseId = "case-c",
                    citedPaperId = "paper-b",
                    claimPaperId = "group-c",
                    failureCode = LayaSystemOneProviderException.CONTEXT_LIMIT_EXCEEDED,
                    measuredSequenceTokenCounts = listOf(900, 1_178, 1_024, 950, 940, 930),
                ),
            ),
        )

        val report = LayaEvaluationReport().render(dataset, run)

        assertTrue(report.contains("DRAFT — NOT RELEASE CALIBRATION EVIDENCE"))
        assertTrue(report.contains("Coverage: 2/3"))
        assertTrue(report.contains("Brier score against adjudicated correctness: 0.265"))
        assertTrue(report.contains("Expected calibration error (10 bins): 0.450"))
        val directnessMetrics = report.lines().single { it.startsWith("- `directness` exact agreement:") }
        assertTrue(directnessMetrics.contains("1/2 (0.500)"))
        assertTrue(directnessMetrics.contains("MAE (n=2): 1.250"))
        assertTrue(report.lines().single { it.startsWith("- Brier score") }.endsWith("; n=2"))
        assertTrue(report.lines().single { it.startsWith("- Expected calibration error") }.endsWith("; n=2"))
        assertTrue(report.contains("SYSTEM_ONE_CONTEXT_LIMIT_EXCEEDED"))
        assertTrue(report.contains("1178"))
        assertTrue(report.contains("Token-limit failures: 1/3 (0.333) (95% CI "))
        assertTrue(report.contains("input total=2000"))
        assertTrue(report.contains("output total=240; output mean=120.000 (95% CI 120.000–120.000)"))
        assertTrue(report.contains("case-b"))
        assertTrue(report.contains("False decisive outcomes"))
        val conflictAgreement = report.lines().single { it.startsWith("- Conflict agreement:") }
        assertTrue(conflictAgreement.contains("/"))
        assertTrue(conflictAgreement.contains("(95% CI "))
        val falseDecisiveOutcomes = report.lines().single { it.startsWith("- False decisive outcomes") }
        assertTrue(falseDecisiveOutcomes.contains("/"))
        assertTrue(falseDecisiveOutcomes.contains("(95% CI "))
        val claimPaperCoverage = report.lines().single { it.startsWith("- Claim–Paper coverage:") }
        assertTrue(claimPaperCoverage.contains("/"))
        assertTrue(claimPaperCoverage.contains("(95% CI "))
        val insufficientAggregation = report.lines().single { it.startsWith("- Predicted INSUFFICIENT_EVIDENCE:") }
        assertTrue(insufficientAggregation.contains("/"))
        assertTrue(insufficientAggregation.contains("(95% CI "))
        assertTrue(report.contains("paper-b"))
        assertTrue(report.contains("Cited-Paper cluster bootstrap"))
        assertTrue(report.contains("| Gold n | Pred n | Precision | Recall |"))
        val supportedMetrics = report.lines().single { it.startsWith("  - `SUPPORTED`:") }
        assertTrue(supportedMetrics.contains("gold n="))
        assertTrue(supportedMetrics.contains("predicted n="))
        assertTrue(!report.contains("Private claim text"))
        assertTrue(!report.contains("Private passage text"))
    }

    private fun dataset() = LayaEvaluationDataset(
        schemaVersion = LayaEvaluationDataset.CURRENT_SCHEMA_VERSION,
        datasetId = "synthetic-report-test",
        datasetVersion = "0.1.0-draft",
        status = LayaEvaluationDataset.DatasetStatus.DRAFT,
        protocolId = "laya-human-calibration-v1-draft",
        candidate = LayaEvaluationDataset.Candidate(
            checkpoint = LayaSystemOneSettings.PINNED_MODEL_ID,
            runtime = LayaSystemOneSettings.PINNED_RUNTIME_VERSION,
            outputMapping = LayaSystemOneSettings.OUTPUT_MAPPING_VERSION,
        ),
        papers = listOf(paper("paper-a"), paper("paper-b")),
        cases = listOf(
            evaluationCase("case-a", "paper-a", "group-a", EvidenceJudgementKind.DIRECT_SUPPORT),
            evaluationCase("case-b", "paper-b", "group-b", EvidenceJudgementKind.CONTRADICTS),
            evaluationCase("case-c", "paper-b", "group-c", EvidenceJudgementKind.PARTIAL_SUPPORT),
        ),
        claimPaperOutcomes = listOf(
            outcome("group-a", "paper-a", LayaEvaluationDataset.ClaimPaperStatus.SUPPORTED),
            outcome("group-b", "paper-b", LayaEvaluationDataset.ClaimPaperStatus.CONTRADICTED),
            outcome("group-c", "paper-b", LayaEvaluationDataset.ClaimPaperStatus.PARTIALLY_SUPPORTED),
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

    private fun paper(id: String) = LayaEvaluationDataset.PaperProvenance(
        citedPaperId = id,
        citation = "Test citation $id",
        sourceUrl = "https://example.test/papers/$id",
        licenseOrRightsBasis = "Synthetic report test only",
        assetSha256 = "a".repeat(64),
    )

    private fun evaluationCase(
        caseId: String,
        paperId: String,
        claimPaperId: String,
        judgement: EvidenceJudgementKind,
    ) = LayaEvaluationDataset.Case(
        caseId = caseId,
        citedPaperId = paperId,
        claimPaperId = claimPaperId,
        split = LayaEvaluationDataset.Split.CALIBRATION,
        sourceLocatorId = "test-only-locator",
        atomicClaim = "Private claim text $caseId",
        evidencePassage = "Private passage text $caseId",
        sectionHeading = "Results",
        adjudicatedLabels = LayaEvaluationDataset.HumanLabels(
            judgement = judgement,
            evidenceRole = EvidenceRole.PRIMARY_FINDING,
            directness = 4,
            claimScopeMatch = 4,
            studyDesignQuality = 3,
            relevance = 4,
        ),
    )

    private fun outcome(
        claimPaperId: String,
        paperId: String,
        status: LayaEvaluationDataset.ClaimPaperStatus,
    ) = LayaEvaluationDataset.ClaimPaperOutcome(
        claimPaperId = claimPaperId,
        citedPaperId = paperId,
        split = LayaEvaluationDataset.Split.CALIBRATION,
        expectedStatus = status,
        expectedConflict = false,
    )

    private fun result(
        caseId: String,
        paperId: String,
        claimPaperId: String,
        prediction: Prediction,
    ) = LayaEvaluationRun.CaseResult(
        caseId = caseId,
        citedPaperId = paperId,
        claimPaperId = claimPaperId,
        prediction = LayaEvaluationRun.Prediction(
            judgement = prediction.judgement,
            evidenceRole = prediction.role,
            confidence = prediction.confidence,
            directness = prediction.directness,
            claimScopeMatch = prediction.claimScopeMatch,
            studyDesignQuality = prediction.studyDesignQuality,
            relevance = prediction.relevance,
        ),
        rawProviderResponseBase64 = Base64.getEncoder().encodeToString("{\"raw\":true}".toByteArray()),
        tokenUsage = LayaEvaluationRun.TokenUsage(inputTokens = 1_000, outputTokens = 120),
    )

    private data class Prediction(
        val judgement: EvidenceJudgementKind,
        val role: EvidenceRole,
        val confidence: Double,
        val directness: Double,
        val claimScopeMatch: Double,
        val studyDesignQuality: Double,
        val relevance: Double,
    )

    private companion object {
        val DIRECT_SUPPORT = EvidenceJudgementKind.DIRECT_SUPPORT
        val PARTIAL_SUPPORT = EvidenceJudgementKind.PARTIAL_SUPPORT
        val CONTRADICTS = EvidenceJudgementKind.CONTRADICTS
        val PRIMARY_FINDING = EvidenceRole.PRIMARY_FINDING
    }
}
