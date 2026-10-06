package com.papertrail.api.calibration

import com.fasterxml.jackson.databind.JsonMappingException
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.papertrail.api.evidence.verification.domain.EvidenceAggregationThresholds
import com.papertrail.api.evidence.verification.domain.EvidenceJudgementKind
import com.papertrail.api.evidence.verification.domain.EvidenceRole
import com.papertrail.api.external.laya.LayaSystemOneSettings
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LayaEvaluationDatasetTest {
    @Test
    fun `accepts a versioned draft dataset with stable paper and outcome groups`() {
        assertDoesNotThrow { draftDataset().validate() }
    }

    @Test
    fun `rejects cases from one Cited Paper split across calibration and held-out data`() {
        val cases = listOf(
            evaluationCase("calibration-case", "paper-1", "claim-paper-1", LayaEvaluationDataset.Split.CALIBRATION),
            evaluationCase("held-out-case", "paper-1", "claim-paper-2", LayaEvaluationDataset.Split.HELD_OUT),
        )
        val outcomes = listOf(
            outcome("claim-paper-1", "paper-1", LayaEvaluationDataset.Split.CALIBRATION),
            outcome("claim-paper-2", "paper-1", LayaEvaluationDataset.Split.HELD_OUT),
        )

        val error = assertThrows(IllegalArgumentException::class.java) {
            draftDataset(cases = cases, outcomes = outcomes).validate()
        }

        assertTrue(error.message.orEmpty().contains("Cited Paper"))
    }

    @Test
    fun `rejects source locator text instead of an opaque stable identifier`() {
        val sourceCase = evaluationCase("case-1", "paper-1", "claim-paper-1", LayaEvaluationDataset.Split.CALIBRATION)
            .copy(sourceLocatorId = "page 1, results paragraph")

        val error = assertThrows(IllegalArgumentException::class.java) {
            draftDataset(cases = listOf(sourceCase)).validate()
        }

        assertTrue(error.message.orEmpty().contains("opaque source locator ID"))
    }

    @Test
    fun `rejects datasets with a different prompt version even when checkpoint is pinned`() {
        val wrongPrompt = pinnedCandidate().copy(promptVersion = "unreviewed-prompt-v2")

        val error = assertThrows(IllegalArgumentException::class.java) {
            draftDataset(candidate = wrongPrompt).validate()
        }

        assertTrue(error.message.orEmpty().contains("prompt"))
    }

    @Test
    fun `requires explicit prompt and context pins in the dataset file`() {
        val mapper = jacksonObjectMapper()
        listOf("promptVersion", "contextLimitTokens").forEach { field ->
            val datasetNode = mapper.valueToTree<ObjectNode>(draftDataset())
            datasetNode.with("candidate").remove(field)

            if (field == "promptVersion") {
                assertThrows(JsonMappingException::class.java) {
                    mapper.treeToValue(datasetNode, LayaEvaluationDataset::class.java)
                }
            } else {
                assertThrows(IllegalArgumentException::class.java) {
                    mapper.treeToValue(datasetNode, LayaEvaluationDataset::class.java).validate()
                }
            }
        }
    }

    @Test
    fun `rejects datasets pinned to any other candidate`() {
        val wrongCandidate = LayaEvaluationDataset.Candidate(
            checkpoint = "unreviewed/model@main",
            runtime = LayaSystemOneSettings.PINNED_RUNTIME_VERSION,
            outputMapping = LayaSystemOneSettings.OUTPUT_MAPPING_VERSION,
            contextLimitTokens = LayaSystemOneSettings.MODEL_CONTEXT_TOKENS,
            promptVersion = LayaSystemOneSettings.PROMPT_VERSION_ID,
        )

        val error = assertThrows(IllegalArgumentException::class.java) {
            draftDataset(candidate = wrongCandidate).validate()
        }

        assertTrue(error.message.orEmpty().contains("exact Laya checkpoint"))
    }

    private fun draftDataset(
        candidate: LayaEvaluationDataset.Candidate = pinnedCandidate(),
        cases: List<LayaEvaluationDataset.Case> = listOf(
            evaluationCase("case-1", "paper-1", "claim-paper-1", LayaEvaluationDataset.Split.CALIBRATION),
        ),
        outcomes: List<LayaEvaluationDataset.ClaimPaperOutcome> = listOf(
            outcome("claim-paper-1", "paper-1", LayaEvaluationDataset.Split.CALIBRATION),
        ),
    ) = LayaEvaluationDataset(
        schemaVersion = LayaEvaluationDataset.CURRENT_SCHEMA_VERSION,
        datasetId = "draft-laya-calibration-test",
        datasetVersion = "0.1.0-draft",
        status = LayaEvaluationDataset.DatasetStatus.DRAFT,
        protocolId = "laya-human-calibration-v1-draft",
        candidate = candidate,
        papers = listOf(paper("paper-1")),
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

    private fun pinnedCandidate() = LayaEvaluationDataset.Candidate(
        checkpoint = LayaSystemOneSettings.PINNED_MODEL_ID,
        runtime = LayaSystemOneSettings.PINNED_RUNTIME_VERSION,
        outputMapping = LayaSystemOneSettings.OUTPUT_MAPPING_VERSION,
        contextLimitTokens = LayaSystemOneSettings.MODEL_CONTEXT_TOKENS,
        promptVersion = LayaSystemOneSettings.PROMPT_VERSION_ID,
    )

    private fun paper(id: String) = LayaEvaluationDataset.PaperProvenance(
        citedPaperId = id,
        citation = "Test citation $id",
        sourceUrl = "https://example.test/papers/$id",
        licenseOrRightsBasis = "DRAFT test-only fixture; no source content is included",
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
        atomicClaim = "The intervention changed the measured outcome.",
        evidencePassage = "The measured outcome changed after the intervention.",
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
}
