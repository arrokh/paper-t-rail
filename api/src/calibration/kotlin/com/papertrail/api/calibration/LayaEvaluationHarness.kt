package com.papertrail.api.calibration

import com.papertrail.api.evidence.verification.domain.AtomicClaimForJudgement
import com.papertrail.api.evidence.verification.domain.EvidencePassageForJudgement
import com.papertrail.api.evidence.verification.domain.SemanticJudgementRequest
import com.papertrail.api.external.laya.LayaEvaluationProvider
import com.papertrail.api.external.laya.LayaSystemOneProviderException
import com.papertrail.api.external.laya.LayaSystemOneSettings
import java.time.Clock
import java.time.Instant
import java.util.Base64
import java.util.UUID

/** Runs one split through the same pinned System One port used by Analysis Runs. */
class LayaEvaluationHarness(
    private val clock: Clock = Clock.systemUTC(),
) {
    fun evaluate(
        dataset: LayaEvaluationDataset,
        split: LayaEvaluationDataset.Split,
        datasetSha256: String,
        applicationRevision: String,
        provider: LayaEvaluationProvider,
        plan: LayaEvaluationPlan? = null,
        planSha256: String? = null,
    ): LayaEvaluationRun {
        dataset.validate()
        require(datasetSha256.matches(SHA256_PATTERN)) { "Dataset file SHA-256 must be a 64-character hexadecimal value." }
        require(applicationRevision.matches(GIT_REVISION_PATTERN)) {
            "Evaluation application revision must be a 40-character commit."
        }
        require(provider.providerId == LayaSystemOneSettings.PROVIDER_ID &&
            provider.version == LayaSystemOneSettings.PROVIDER_VERSION &&
            provider.modelId == LayaSystemOneSettings.PINNED_MODEL_ID
        ) { "Laya evaluation requires the exact pinned Laya provider, runtime, and checkpoint." }
        require((plan == null) == (planSha256 == null) &&
            (planSha256 == null || planSha256.matches(SHA256_PATTERN))
        ) { "Pass the pre-registration plan and its file SHA-256 together." }
        plan?.let {
            it.validateAgainst(dataset, datasetSha256)
            require(it.applicationRevision == applicationRevision) {
                "Evaluation application revision differs from the pre-registered revision."
            }
        }

        val cases = dataset.cases.filter { it.split == split }
        require(cases.isNotEmpty()) { "Laya evaluation dataset has no cases in split '$split'." }
        if (split == LayaEvaluationDataset.Split.HELD_OUT) {
            require(dataset.status == LayaEvaluationDataset.DatasetStatus.HUMAN_REVIEWED) {
                "Held-out evaluation requires a fully HUMAN_REVIEWED dataset."
            }
            val frozenPlan = requireNotNull(plan) {
                "Held-out evaluation requires a separately versioned, pre-registered safety bar and split."
            }
            require(Instant.parse(frozenPlan.approvedAt).isBefore(Instant.now(clock))) {
                "Held-out evaluation must start after its safety bar and split were pre-registered."
            }
        }

        val caseResults = cases.map { case -> evaluateCase(case, provider) }
        return LayaEvaluationRun(
            schemaVersion = LayaEvaluationRun.CURRENT_SCHEMA_VERSION,
            datasetId = dataset.datasetId,
            datasetSha256 = datasetSha256,
            split = split,
            candidate = dataset.candidate,
            applicationRevision = applicationRevision,
            evaluatedAt = Instant.now(clock).toString(),
            planId = plan?.planId,
            planSha256 = planSha256,
            caseResults = caseResults,
        ).also { it.validateAgainst(dataset) }
    }

    private fun evaluateCase(
        case: LayaEvaluationDataset.Case,
        provider: LayaEvaluationProvider,
    ): LayaEvaluationRun.CaseResult {
        val claimId = stableId("${case.caseId}:claim")
        val passageId = stableId("${case.caseId}:passage")
        return try {
            val evaluation = provider.evaluateForCalibration(
                SemanticJudgementRequest(
                    atomicClaim = AtomicClaimForJudgement(claimId, case.atomicClaim),
                    evidencePassages = listOf(
                        EvidencePassageForJudgement(passageId, case.evidencePassage, case.sectionHeading),
                    ),
                ),
            )
            val judgement = evaluation.result.evidenceJudgements.singleOrNull()
                ?: return case.failure("SYSTEM_ONE_UNEXPECTED_RESULT_COUNT")
            val rawResponse = evaluation.rawResponseBytesByPassageId[passageId]
                ?: return case.failure("SYSTEM_ONE_RAW_OUTPUT_MISSING")
            val tokenUsage = evaluation.tokenUsageByPassageId[passageId]
                ?: return case.failure("SYSTEM_ONE_TOKEN_USAGE_MISSING")
            case.success(
                LayaEvaluationRun.Prediction.from(judgement),
                Base64.getEncoder().encodeToString(rawResponse),
                LayaEvaluationRun.TokenUsage(tokenUsage.inputTokens, tokenUsage.outputTokens),
            )
        } catch (exception: LayaSystemOneProviderException) {
            case.failure(
                exception.failureReasonCode ?: SYSTEM_ONE_PROVIDER_FAILURE,
                exception.measuredSequenceTokenCounts,
            )
        } catch (_: Exception) {
            case.failure(SYSTEM_ONE_PROVIDER_FAILURE)
        }
    }

    private fun LayaEvaluationDataset.Case.success(
        prediction: LayaEvaluationRun.Prediction,
        rawProviderResponseBase64: String,
        tokenUsage: LayaEvaluationRun.TokenUsage,
    ): LayaEvaluationRun.CaseResult = LayaEvaluationRun.CaseResult(
        caseId = caseId,
        citedPaperId = citedPaperId,
        claimPaperId = claimPaperId,
        prediction = prediction,
        rawProviderResponseBase64 = rawProviderResponseBase64,
        tokenUsage = tokenUsage,
    )

    private fun LayaEvaluationDataset.Case.failure(
        code: String,
        measuredSequenceTokenCounts: List<Int> = emptyList(),
    ): LayaEvaluationRun.CaseResult = LayaEvaluationRun.CaseResult(
        caseId = caseId,
        citedPaperId = citedPaperId,
        claimPaperId = claimPaperId,
        failureCode = code.takeIf { it.matches(FAILURE_CODE_PATTERN) } ?: SYSTEM_ONE_PROVIDER_FAILURE,
        measuredSequenceTokenCounts = measuredSequenceTokenCounts,
    )

    private fun stableId(value: String): UUID = UUID.nameUUIDFromBytes(value.toByteArray(Charsets.UTF_8))

    companion object {
        const val SYSTEM_ONE_PROVIDER_FAILURE = "SYSTEM_ONE_PROVIDER_FAILURE"
        private val SHA256_PATTERN = Regex("[0-9a-fA-F]{64}")
        private val GIT_REVISION_PATTERN = Regex("[0-9a-f]{40}")
        private val FAILURE_CODE_PATTERN = Regex("[A-Z][A-Z0-9_]{0,127}")
    }
}
