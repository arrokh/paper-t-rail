package com.papertrail.api.calibration

import com.papertrail.api.evidence.verification.domain.AtomicClaimForJudgement
import com.papertrail.api.evidence.verification.domain.EvidenceAggregationThresholds
import com.papertrail.api.evidence.verification.domain.EvidenceJudgement
import com.papertrail.api.evidence.verification.domain.EvidenceJudgementKind
import com.papertrail.api.evidence.verification.domain.EvidencePassageForJudgement
import com.papertrail.api.evidence.verification.domain.EvidenceRole
import com.papertrail.api.evidence.verification.domain.SemanticJudgementRequest
import com.papertrail.api.evidence.verification.domain.SemanticJudgementResult
import com.papertrail.api.evidence.verification.provider.LayaSystemOneProviderException
import com.papertrail.api.evidence.verification.provider.LayaEvaluationProvider
import com.papertrail.api.evidence.verification.provider.LayaSystemOneSettings
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class LayaEvaluationHarnessTest {
    private val harness = LayaEvaluationHarness(
        Clock.fixed(Instant.parse("2026-09-28T12:00:00Z"), ZoneOffset.UTC),
    )

    @Test
    fun `runs only requested split through the pinned provider and stores text-free results`() {
        val provider = RecordingProvider()
        val dataset = dataset(
            cases = listOf(
                evaluationCase("calibration-case", "paper-cal", "claim-paper-cal", LayaEvaluationDataset.Split.CALIBRATION),
                evaluationCase("held-out-case", "paper-test", "claim-paper-test", LayaEvaluationDataset.Split.HELD_OUT),
            ),
            outcomes = listOf(
                outcome("claim-paper-cal", "paper-cal", LayaEvaluationDataset.Split.CALIBRATION),
                outcome("claim-paper-test", "paper-test", LayaEvaluationDataset.Split.HELD_OUT),
            ),
        )

        val run = harness.evaluate(
            dataset = dataset,
            split = LayaEvaluationDataset.Split.CALIBRATION,
            datasetSha256 = "b".repeat(64),
            applicationRevision = "a".repeat(40),
            provider = provider,
        )

        assertEquals(listOf("calibration-case"), run.caseResults.map(LayaEvaluationRun.CaseResult::caseId))
        assertEquals(1, provider.requests.size)
        assertEquals("The claim text for case calibration-case.", provider.requests.single().atomicClaim.text)
        assertEquals("The evidence text for case calibration-case.", provider.requests.single().evidencePassages.single().text)
        assertEquals(LayaEvaluationDataset.Split.CALIBRATION, run.split)
        assertEquals(LayaSystemOneSettings.PINNED_MODEL_ID, run.candidate.checkpoint)
        assertEquals(EvidenceJudgementKind.DIRECT_SUPPORT, run.caseResults.single().prediction?.judgement)
        assertEquals(1_000, run.caseResults.single().tokenUsage?.inputTokens)
        assertTrue(run.caseResults.single().rawProviderResponseBase64 != null)
        assertFalse(run.toString().contains("The claim text"))
        assertFalse(run.toString().contains("The evidence text"))
    }

    @Test
    fun `records context-limit failures as incomplete work without inventing judgements`() {
        val provider = RecordingProvider().apply {
            failure = LayaSystemOneProviderException(
                "context rejected",
                LayaSystemOneProviderException.CONTEXT_LIMIT_EXCEEDED,
                listOf(900, 1_178, 1_024, 950, 940, 930),
            )
        }

        val run = harness.evaluate(
            dataset = dataset(),
            split = LayaEvaluationDataset.Split.CALIBRATION,
            datasetSha256 = "c".repeat(64),
            applicationRevision = "a".repeat(40),
            provider = provider,
        )

        assertNull(run.caseResults.single().prediction)
        assertEquals(
            LayaSystemOneProviderException.CONTEXT_LIMIT_EXCEEDED,
            run.caseResults.single().failureCode,
        )
        assertEquals(listOf(900, 1_178, 1_024, 950, 940, 930), run.caseResults.single().measuredSequenceTokenCounts)
    }

    @Test
    fun `rejects held-out inference until human labels and a pre-registration exist`() {
        val provider = RecordingProvider()
        val dataset = dataset(
            cases = listOf(evaluationCase("held-out-case", "paper-test", "claim-paper-test", LayaEvaluationDataset.Split.HELD_OUT)),
            outcomes = listOf(outcome("claim-paper-test", "paper-test", LayaEvaluationDataset.Split.HELD_OUT)),
        )

        val error = assertThrows(IllegalArgumentException::class.java) {
            harness.evaluate(
                dataset = dataset,
                split = LayaEvaluationDataset.Split.HELD_OUT,
                datasetSha256 = "d".repeat(64),
                applicationRevision = "a".repeat(40),
                provider = provider,
            )
        }

        assertTrue(error.message.orEmpty().contains("HUMAN_REVIEWED"))
        assertTrue(provider.requests.isEmpty())
    }

    @Test
    fun `refuses providers that do not identify the approved model and runtime`() {
        val provider = RecordingProvider().apply { modelId = "different-model" }

        val error = assertThrows(IllegalArgumentException::class.java) {
            harness.evaluate(
                dataset = dataset(),
                split = LayaEvaluationDataset.Split.CALIBRATION,
                datasetSha256 = "e".repeat(64),
                applicationRevision = "a".repeat(40),
                provider = provider,
            )
        }

        assertTrue(error.message.orEmpty().contains("exact pinned Laya provider"))
        assertTrue(provider.requests.isEmpty())
    }

    private fun dataset(
        cases: List<LayaEvaluationDataset.Case> = listOf(
            evaluationCase("calibration-case", "paper-cal", "claim-paper-cal", LayaEvaluationDataset.Split.CALIBRATION),
        ),
        outcomes: List<LayaEvaluationDataset.ClaimPaperOutcome> = listOf(
            outcome("claim-paper-cal", "paper-cal", LayaEvaluationDataset.Split.CALIBRATION),
        ),
    ) = LayaEvaluationDataset(
        schemaVersion = LayaEvaluationDataset.CURRENT_SCHEMA_VERSION,
        datasetId = "draft-evaluation-dataset",
        datasetVersion = "0.1.0-draft",
        status = LayaEvaluationDataset.DatasetStatus.DRAFT,
        protocolId = "laya-human-calibration-v1-draft",
        candidate = LayaEvaluationDataset.Candidate(
            checkpoint = LayaSystemOneSettings.PINNED_MODEL_ID,
            runtime = LayaSystemOneSettings.PINNED_RUNTIME_VERSION,
            outputMapping = LayaSystemOneSettings.OUTPUT_MAPPING_VERSION,
        ),
        papers = cases.map(LayaEvaluationDataset.Case::citedPaperId).distinct().map(::paper),
        cases = cases,
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

    private fun paper(id: String) = LayaEvaluationDataset.PaperProvenance(
        citedPaperId = id,
        citation = "Test citation $id",
        sourceUrl = "https://example.test/papers/$id",
        licenseOrRightsBasis = "Test-only DRAFT fixture; no source content is included",
        assetSha256 = "a".repeat(64),
    )

    private fun evaluationCase(
        caseId: String,
        citedPaperId: String,
        claimPaperId: String,
        split: LayaEvaluationDataset.Split,
    ) = LayaEvaluationDataset.Case(
        caseId = caseId,
        citedPaperId = citedPaperId,
        claimPaperId = claimPaperId,
        split = split,
        sourceLocatorId = "page-1-results-paragraph-1",
        atomicClaim = "The claim text for case $caseId.",
        evidencePassage = "The evidence text for case $caseId.",
        sectionHeading = "Results",
        adjudicatedLabels = LayaEvaluationDataset.HumanLabels(
            judgement = EvidenceJudgementKind.DIRECT_SUPPORT,
            evidenceRole = EvidenceRole.PRIMARY_FINDING,
            directness = 4,
            claimScopeMatch = 4,
            studyDesignQuality = 3,
            relevance = 4,
        ),
    )

    private fun outcome(
        claimPaperId: String,
        citedPaperId: String,
        split: LayaEvaluationDataset.Split,
    ) = LayaEvaluationDataset.ClaimPaperOutcome(
        claimPaperId = claimPaperId,
        citedPaperId = citedPaperId,
        split = split,
        expectedStatus = LayaEvaluationDataset.ClaimPaperStatus.SUPPORTED,
        expectedConflict = false,
    )

    private class RecordingProvider : LayaEvaluationProvider {
        override val providerId = LayaSystemOneSettings.PROVIDER_ID
        override val version = LayaSystemOneSettings.PROVIDER_VERSION
        override var modelId: String? = LayaSystemOneSettings.PINNED_MODEL_ID
        val requests = mutableListOf<SemanticJudgementRequest>()
        var failure: Exception? = null

        override fun evaluate(request: SemanticJudgementRequest): SemanticJudgementResult =
            evaluateForCalibration(request).result

        override fun evaluateForCalibration(request: SemanticJudgementRequest): LayaEvaluationProvider.Evaluation {
            requests += request
            failure?.let { throw it }
            val result = SemanticJudgementResult(
                request.evidencePassages.map { passage ->
                    EvidenceJudgement(
                        evidenceCandidateId = passage.id,
                        judgement = EvidenceJudgementKind.DIRECT_SUPPORT,
                        evidenceRole = EvidenceRole.PRIMARY_FINDING,
                        confidence = 0.8,
                        directness = 1.0,
                        claimScopeMatch = 1.0,
                        studyDesignQuality = 0.75,
                        relevance = 1.0,
                    )
                },
            )
            val rawResponses = request.evidencePassages.associate { passage ->
                passage.id to "{\"judgement\":\"DIRECT_SUPPORT\"}".toByteArray()
            }
            val tokenUsage = request.evidencePassages.associate { passage ->
                passage.id to LayaEvaluationProvider.TokenUsage(inputTokens = 1_000, outputTokens = 120)
            }
            return LayaEvaluationProvider.Evaluation(result, rawResponses, tokenUsage)
        }
    }
}
