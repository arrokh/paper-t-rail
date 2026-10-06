package com.papertrail.api.calibration

import com.papertrail.api.evidence.verification.domain.EvidenceJudgement
import com.papertrail.api.evidence.verification.domain.EvidenceJudgementKind
import com.papertrail.api.evidence.verification.domain.EvidenceRole
import com.papertrail.api.external.laya.LayaSystemOneProviderException
import com.papertrail.api.external.laya.LayaSystemOneSettings
import java.time.Instant
import java.util.Base64
import java.util.UUID

/** Frozen, text-free outputs from one Laya evaluation split. */
data class LayaEvaluationRun(
    val schemaVersion: Int,
    val datasetId: String,
    val datasetSha256: String,
    val split: LayaEvaluationDataset.Split,
    val candidate: LayaEvaluationDataset.Candidate,
    val applicationRevision: String,
    val evaluatedAt: String,
    val planId: String? = null,
    val planSha256: String? = null,
    val caseResults: List<CaseResult>,
) {
    fun validateAgainst(dataset: LayaEvaluationDataset) {
        require(schemaVersion == CURRENT_SCHEMA_VERSION) { "Unsupported Laya evaluation run schemaVersion '$schemaVersion'." }
        require(datasetId == dataset.datasetId) { "Laya evaluation run belongs to another dataset." }
        require(datasetSha256.matches(SHA256_PATTERN)) { "Laya evaluation run must pin the dataset SHA-256." }
        require(candidate == dataset.candidate) { "Laya evaluation run candidate differs from the dataset candidate." }
        require(applicationRevision.matches(GIT_REVISION_PATTERN) && isIsoInstant(evaluatedAt)) {
            "Laya evaluation run must pin a 40-character application commit and evaluation time."
        }
        require((planId == null) == (planSha256 == null) &&
            (planId == null || planId.isNotBlank() && planSha256!!.matches(SHA256_PATTERN))
        ) { "Laya evaluation run must pin both the pre-registration ID and SHA-256, or neither." }
        require(caseResults.map(CaseResult::caseId).distinct().size == caseResults.size) {
            "Laya evaluation run case identifiers must be unique."
        }
        val expectedCases = dataset.cases.filter { it.split == split }.associateBy(LayaEvaluationDataset.Case::caseId)
        require(expectedCases.isNotEmpty()) { "Laya evaluation split '$split' must contain at least one case." }
        require(caseResults.size == expectedCases.size && caseResults.all { it.caseId in expectedCases }) {
            "Laya evaluation run must contain exactly the cases from its selected split."
        }
        caseResults.forEach(CaseResult::validate)
        caseResults.forEach { result ->
            require(result.citedPaperId == expectedCases.getValue(result.caseId).citedPaperId &&
                result.claimPaperId == expectedCases.getValue(result.caseId).claimPaperId
            ) { "Laya evaluation run case '${result.caseId}' has mismatched paper/group identifiers." }
        }
    }

    data class CaseResult(
        val caseId: String,
        val citedPaperId: String,
        val claimPaperId: String,
        val prediction: Prediction? = null,
        val failureCode: String? = null,
        val rawProviderResponseBase64: String? = null,
        val tokenUsage: TokenUsage? = null,
        val measuredSequenceTokenCounts: List<Int> = emptyList(),
    ) {
        fun validate() {
            require(caseId.isNotBlank() && citedPaperId.isNotBlank() && claimPaperId.isNotBlank()) {
                "Laya evaluation run case results require stable case and paper identifiers."
            }
            require((prediction != null) xor (failureCode != null)) {
                "Each Laya evaluation run case must contain exactly one prediction or failure code."
            }
            require(failureCode == null || failureCode.matches(FAILURE_CODE_PATTERN)) {
                "Laya evaluation run failure codes must be stable identifiers, not provider messages."
            }
            require(if (prediction != null) {
                rawProviderResponseBase64 != null && tokenUsage != null && failureCode == null && measuredSequenceTokenCounts.isEmpty()
            } else {
                rawProviderResponseBase64 == null && tokenUsage == null && failureCode != null
            }) {
                "Completed Laya case results must preserve mapped output, raw response, and usage; failures must not invent them."
            }
            tokenUsage?.validate()
            require(measuredSequenceTokenCounts.all { it > 0 } &&
                (measuredSequenceTokenCounts.isEmpty() ||
                    measuredSequenceTokenCounts.size == QUESTION_SEQUENCE_COUNT &&
                    failureCode == LayaSystemOneProviderException.CONTEXT_LIMIT_EXCEEDED)
            ) { "Measured sequence token counts are only valid as six positive counts for context-limit failures." }
            rawProviderResponseBase64?.let { encoded ->
                val bytes = runCatching { Base64.getDecoder().decode(encoded) }.getOrNull()
                require(bytes != null && bytes.isNotEmpty() && bytes.size <= LayaSystemOneSettings.MAX_RESPONSE_BYTES) {
                    "Raw Laya provider responses must be base64-encoded and within the configured response size limit."
                }
            }
            prediction?.validate()
        }
    }

    data class TokenUsage(
        val inputTokens: Long,
        val outputTokens: Long,
    ) {
        fun validate() {
            require(inputTokens > 0 && outputTokens >= 0) {
                "Laya evaluation token usage requires positive input and non-negative output counts."
            }
        }
    }

    data class Prediction(
        val judgement: EvidenceJudgementKind,
        val evidenceRole: EvidenceRole,
        val confidence: Double,
        val directness: Double,
        val claimScopeMatch: Double,
        val studyDesignQuality: Double,
        val relevance: Double,
    ) {
        fun validate() {
            require(listOf(confidence, directness, claimScopeMatch, studyDesignQuality, relevance).all { it in 0.0..1.0 }) {
                "Laya evaluation run scores must be finite values between zero and one."
            }
        }

        fun toJudgement(evidenceCandidateId: UUID): EvidenceJudgement = EvidenceJudgement(
            evidenceCandidateId = evidenceCandidateId,
            judgement = judgement,
            evidenceRole = evidenceRole,
            confidence = confidence,
            directness = directness,
            claimScopeMatch = claimScopeMatch,
            studyDesignQuality = studyDesignQuality,
            relevance = relevance,
        )

        companion object {
            fun from(judgement: EvidenceJudgement) = Prediction(
                judgement = judgement.judgement,
                evidenceRole = judgement.evidenceRole,
                confidence = judgement.confidence,
                directness = judgement.directness,
                claimScopeMatch = judgement.claimScopeMatch,
                studyDesignQuality = judgement.studyDesignQuality,
                relevance = judgement.relevance,
            )
        }
    }

    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
        private val SHA256_PATTERN = Regex("[0-9a-fA-F]{64}")
        private val GIT_REVISION_PATTERN = Regex("[0-9a-f]{40}")
        private val FAILURE_CODE_PATTERN = Regex("[A-Z][A-Z0-9_]{0,127}")
        private const val QUESTION_SEQUENCE_COUNT = 6

        private fun isIsoInstant(value: String): Boolean = try {
            Instant.parse(value)
            true
        } catch (_: Exception) {
            false
        }
    }
}
