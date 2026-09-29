package com.papertrail.api.calibration

import com.papertrail.api.evidence.verification.domain.EvidenceAggregationThresholds
import com.papertrail.api.evidence.verification.domain.EvidenceJudgementKind
import com.papertrail.api.evidence.verification.domain.EvidenceRole
import com.papertrail.api.evidence.verification.provider.LayaSystemOneProviderException
import com.papertrail.api.evidence.verification.provider.LayaSystemOneSettings
import com.papertrail.api.scholarly.references.resolver.ReferenceResolutionStatus
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

    @Test
    fun `does not mark count-based safety criteria as met when no held-out groups completed`() {
        val dataset = humanReviewedDataset()
        val datasetSha256 = "d".repeat(64)
        val planSha256 = "e".repeat(64)
        val plan = LayaEvaluationPlan(
            schemaVersion = LayaEvaluationPlan.CURRENT_SCHEMA_VERSION,
            planId = "reviewed-plan-test-v1",
            datasetId = dataset.datasetId,
            datasetVersion = dataset.datasetVersion,
            datasetSha256 = datasetSha256,
            heldOutSplitSha256 = dataset.heldOutSplitSha256(),
            candidate = dataset.candidate,
            approvedBy = "test-reviewer",
            approvedAt = "2026-09-28T11:00:00Z",
            applicationRevision = "a".repeat(40),
            heldOutCitedPaperIds = setOf("paper-held-out"),
            uncertaintyMethodId = LayaEvaluationDataset.UNCERTAINTY_METHOD_ID,
            safetyBar = listOf(
                LayaEvaluationPlan.SafetyBarCriterion(
                    metricId = "aggregation.design-baseline.false_decisive_count",
                    comparison = LayaEvaluationPlan.Comparison.AT_MOST,
                    threshold = 0.0,
                    rationale = "Synthetic test-only criterion.",
                ),
                LayaEvaluationPlan.SafetyBarCriterion(
                    metricId = "high_risk.error_count",
                    comparison = LayaEvaluationPlan.Comparison.AT_MOST,
                    threshold = 0.0,
                    rationale = "Synthetic test-only criterion.",
                ),
            ),
        )
        val run = LayaEvaluationRun(
            schemaVersion = LayaEvaluationRun.CURRENT_SCHEMA_VERSION,
            datasetId = dataset.datasetId,
            datasetSha256 = datasetSha256,
            split = LayaEvaluationDataset.Split.HELD_OUT,
            candidate = dataset.candidate,
            applicationRevision = plan.applicationRevision,
            evaluatedAt = "2026-09-28T12:00:00Z",
            planId = plan.planId,
            planSha256 = planSha256,
            caseResults = dataset.cases
                .filter { it.split == LayaEvaluationDataset.Split.HELD_OUT }
                .map { case ->
                    LayaEvaluationRun.CaseResult(
                        caseId = case.caseId,
                        citedPaperId = case.citedPaperId,
                        claimPaperId = case.claimPaperId,
                        failureCode = "SYSTEM_ONE_PROVIDER_FAILURE",
                    )
                },
        )

        val report = LayaEvaluationReport().render(
            dataset = dataset,
            run = run,
            plan = plan,
            frozenOutputSha256 = "f".repeat(64),
            preRegistrationSha256 = planSha256,
        )

        assertTrue(report.contains("`aggregation.design-baseline.false_decisive_count` AT_MOST 0.000: actual not estimable — NOT EVALUABLE"))
        assertTrue(report.contains("`high_risk.error_count` AT_MOST 0.000: actual not estimable — NOT EVALUABLE"))
    }

    private fun humanReviewedDataset(): LayaEvaluationDataset {
        data class CaseSpec(
            val caseId: String,
            val claimPaperId: String,
            val judgement: EvidenceJudgementKind,
            val role: EvidenceRole,
            val claimScopeMatch: Int = 4,
            val coverageTags: Set<LayaEvaluationDataset.CoverageTag> = emptySet(),
        )

        val heldOutCases = listOf(
            CaseSpec(
                "case-conflict-support",
                "group-conflict",
                EvidenceJudgementKind.DIRECT_SUPPORT,
                EvidenceRole.PRIMARY_FINDING,
                claimScopeMatch = 0,
                coverageTags = setOf(LayaEvaluationDataset.CoverageTag.SCOPE_OR_QUALIFIER_MISMATCH),
            ),
            CaseSpec(
                "case-conflict-contradiction",
                "group-conflict",
                EvidenceJudgementKind.CONTRADICTS,
                EvidenceRole.SECONDARY_REPORT,
                coverageTags = setOf(LayaEvaluationDataset.CoverageTag.SECONDARY_REPORT),
            ),
            CaseSpec("case-supported", "group-supported", EvidenceJudgementKind.DIRECT_SUPPORT, EvidenceRole.PRIMARY_FINDING),
            CaseSpec("case-partial", "group-partial", EvidenceJudgementKind.PARTIAL_SUPPORT, EvidenceRole.AUTHOR_SYNTHESIS),
            CaseSpec("case-contradicted", "group-contradicted", EvidenceJudgementKind.CONTRADICTS, EvidenceRole.PRIMARY_FINDING),
            CaseSpec("case-unrelated", "group-unrelated", EvidenceJudgementKind.UNRELATED, EvidenceRole.AUTHOR_SYNTHESIS),
            CaseSpec(
                "case-abstention",
                "group-abstention",
                EvidenceJudgementKind.INSUFFICIENT,
                EvidenceRole.PRIMARY_FINDING,
                coverageTags = setOf(LayaEvaluationDataset.CoverageTag.ABSTENTION),
            ),
        )
        val calibrationCase = reviewedCase(
            caseId = "case-calibration",
            claimPaperId = "group-calibration",
            citedPaperId = "paper-calibration",
            split = LayaEvaluationDataset.Split.CALIBRATION,
            judgement = EvidenceJudgementKind.DIRECT_SUPPORT,
            role = EvidenceRole.PRIMARY_FINDING,
        )
        val reviewedHeldOutCases = heldOutCases.map { spec ->
            reviewedCase(
                caseId = spec.caseId,
                claimPaperId = spec.claimPaperId,
                citedPaperId = "paper-held-out",
                split = LayaEvaluationDataset.Split.HELD_OUT,
                judgement = spec.judgement,
                role = spec.role,
                claimScopeMatch = spec.claimScopeMatch,
                coverageTags = spec.coverageTags,
            )
        }
        val outcomes = listOf(
            reviewedOutcome("group-calibration", "paper-calibration", LayaEvaluationDataset.Split.CALIBRATION, LayaEvaluationDataset.ClaimPaperStatus.SUPPORTED),
            reviewedOutcome("group-conflict", "paper-held-out", LayaEvaluationDataset.Split.HELD_OUT, LayaEvaluationDataset.ClaimPaperStatus.INSUFFICIENT_EVIDENCE, true),
            reviewedOutcome("group-supported", "paper-held-out", LayaEvaluationDataset.Split.HELD_OUT, LayaEvaluationDataset.ClaimPaperStatus.SUPPORTED),
            reviewedOutcome("group-partial", "paper-held-out", LayaEvaluationDataset.Split.HELD_OUT, LayaEvaluationDataset.ClaimPaperStatus.PARTIALLY_SUPPORTED),
            reviewedOutcome("group-contradicted", "paper-held-out", LayaEvaluationDataset.Split.HELD_OUT, LayaEvaluationDataset.ClaimPaperStatus.CONTRADICTED),
            reviewedOutcome("group-unrelated", "paper-held-out", LayaEvaluationDataset.Split.HELD_OUT, LayaEvaluationDataset.ClaimPaperStatus.INSUFFICIENT_EVIDENCE),
            reviewedOutcome("group-abstention", "paper-held-out", LayaEvaluationDataset.Split.HELD_OUT, LayaEvaluationDataset.ClaimPaperStatus.INSUFFICIENT_EVIDENCE),
        )

        return LayaEvaluationDataset(
            schemaVersion = LayaEvaluationDataset.CURRENT_SCHEMA_VERSION,
            datasetId = "synthetic-human-review-report-test",
            datasetVersion = "0.1.0-test-only",
            status = LayaEvaluationDataset.DatasetStatus.HUMAN_REVIEWED,
            protocolId = "laya-human-calibration-v1",
            candidate = dataset().candidate,
            papers = listOf(reviewedPaper("paper-calibration"), reviewedPaper("paper-held-out")),
            cases = listOf(calibrationCase) + reviewedHeldOutCases,
            claimPaperOutcomes = outcomes,
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

    private fun reviewedPaper(id: String) = LayaEvaluationDataset.PaperProvenance(
        citedPaperId = id,
        citation = "Synthetic test-only citation $id",
        sourceUrl = "https://example.test/papers/$id",
        licenseOrRightsBasis = "Synthetic test-only provenance; no source content",
        resolutionStatus = ReferenceResolutionStatus.RESOLVED,
        assetSha256 = "a".repeat(64),
        rightsReviewedBy = "test-reviewer",
        rightsReviewedAt = "2026-09-28T10:00:00Z",
    )

    private fun reviewedCase(
        caseId: String,
        claimPaperId: String,
        citedPaperId: String,
        split: LayaEvaluationDataset.Split,
        judgement: EvidenceJudgementKind,
        role: EvidenceRole,
        claimScopeMatch: Int = 4,
        coverageTags: Set<LayaEvaluationDataset.CoverageTag> = emptySet(),
    ): LayaEvaluationDataset.Case {
        val labels = LayaEvaluationDataset.HumanLabels(judgement, role, 4, claimScopeMatch, 3, 4)
        return LayaEvaluationDataset.Case(
            caseId = caseId,
            citedPaperId = citedPaperId,
            claimPaperId = claimPaperId,
            split = split,
            sourceLocatorId = "$caseId-locator",
            atomicClaim = "Synthetic test-only claim $caseId",
            evidencePassage = "Synthetic test-only passage $caseId",
            sectionHeading = "Results",
            adjudicatedLabels = labels,
            independentReviews = listOf(
                LayaEvaluationDataset.ReviewAnnotation("reviewer-one", "synthetic fixture reviewer", "2026-09-28T10:00:00Z", "Synthetic test-only annotation.", labels),
            ),
            adjudicatorId = "adjudicator-one",
            adjudicatedAt = "2026-09-28T10:30:00Z",
            adjudicationRationale = "Synthetic test-only adjudication.",
            coverageTags = coverageTags,
        )
    }

    private fun reviewedOutcome(
        claimPaperId: String,
        citedPaperId: String,
        split: LayaEvaluationDataset.Split,
        status: LayaEvaluationDataset.ClaimPaperStatus,
        conflict: Boolean = false,
    ) = LayaEvaluationDataset.ClaimPaperOutcome(
        claimPaperId = claimPaperId,
        citedPaperId = citedPaperId,
        split = split,
        expectedStatus = status,
        expectedConflict = conflict,
        adjudicatorId = "adjudicator-one",
        adjudicatedAt = "2026-09-28T10:30:00Z",
        rationale = "Synthetic test-only outcome.",
        independentReviews = listOf(
            LayaEvaluationDataset.ClaimPaperReview("reviewer-one", "synthetic fixture reviewer", "2026-09-28T10:00:00Z", "Synthetic test-only review.", status, conflict),
        ),
    )

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
            contextLimitTokens = LayaSystemOneSettings.MODEL_CONTEXT_TOKENS,
            promptVersion = LayaSystemOneSettings.PROMPT_VERSION_ID,
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
