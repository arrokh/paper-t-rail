package com.papertrail.api.external.laya

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.papertrail.api.analysis.execution.service.AnalysisRunExecutionService
import com.papertrail.api.evidence.verification.domain.EvidenceJudgement
import com.papertrail.api.evidence.verification.domain.EvidenceJudgementKind
import com.papertrail.api.evidence.verification.domain.EvidencePassageForJudgement
import com.papertrail.api.evidence.verification.domain.EvidenceRole
import com.papertrail.api.evidence.verification.domain.SemanticJudgementRequest
import com.papertrail.api.evidence.verification.domain.SemanticJudgementResult
import org.springframework.stereotype.Component
import com.papertrail.api.infrastructure.http.BoundedHttpClient
import com.papertrail.api.infrastructure.http.BoundedHttpRequestException
import java.io.IOException
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID

import com.papertrail.api.evidence.verification.provider.SystemOneEvidenceJudgementContract
import com.papertrail.api.evidence.verification.provider.SystemOneProvider
import com.papertrail.api.evidence.verification.provider.SystemOneRequestPreflight

@Component
class LayaSystemOneProvider(
    private val settings: LayaSystemOneSettings,
    private val objectMapper: ObjectMapper,
    private val executionService: AnalysisRunExecutionService? = null,
) : LayaEvaluationProvider, SystemOneRequestPreflight {
    override val providerId = LayaSystemOneSettings.PROVIDER_ID
    override val version = LayaSystemOneSettings.PROVIDER_VERSION
    override val modelId = LayaSystemOneSettings.PINNED_MODEL_ID

    private val httpClient = BoundedHttpClient(CONNECT_TIMEOUT_SECONDS)

    override fun tokenCounts(claim: String, passage: EvidencePassageForJudgement): List<Int> {
        requireConfigured()
        if (claim.isBlank() || passage.text.isBlank()) {
            throw LayaSystemOneProviderException("Laya System One requires non-empty Atomic Claims and Evidence Passages.")
        }
        val preparedRequest = createRequest(claim, passage, preflight = true)
        executionService?.captureCurrentSystemOneRequest(preparedRequest.body)
        val response = sendRequest(preparedRequest.request)
        executionService?.captureCurrentSystemOneResponse(response.body(), "system-one-preflight-response-v1")
        if (response.statusCode() !in 200..299) {
            throw LayaSystemOneProviderException("Laya System One preflight returned HTTP ${response.statusCode()}.")
        }
        val body = try {
            objectMapper.readTree(response.body())
        } catch (_: IOException) {
            throw LayaSystemOneProviderException("Laya System One preflight returned a malformed response.")
        }
        val counts = body?.get("tokenCounts")
        if (body?.path("contextLimit")?.asInt() != LayaSystemOneSettings.MODEL_CONTEXT_TOKENS ||
            counts == null || !counts.isArray || counts.size() != QUESTION_SEQUENCE_COUNT ||
            counts.any { !it.isIntegralNumber || !it.canConvertToInt() || it.intValue() < 0 }
        ) {
            throw LayaSystemOneProviderException("Laya System One preflight returned unsupported token counts.")
        }
        return counts.map { it.intValue() }
    }

    override fun evaluate(request: SemanticJudgementRequest): SemanticJudgementResult =
        evaluateRequest(request, captureRawResponses = false).result

    override fun evaluateForCalibration(request: SemanticJudgementRequest): LayaEvaluationProvider.Evaluation =
        evaluateRequest(request, captureRawResponses = true)

    private fun evaluateRequest(
        request: SemanticJudgementRequest,
        captureRawResponses: Boolean,
    ): LayaEvaluationProvider.Evaluation {
        if (request.evidencePassages.isEmpty()) {
            return LayaEvaluationProvider.Evaluation(SemanticJudgementResult(emptyList()), emptyMap(), emptyMap())
        }
        requireConfigured()
        if (request.atomicClaim.text.isBlank() || request.evidencePassages.any { it.text.isBlank() }) {
            throw LayaSystemOneProviderException("Laya System One requires non-empty Atomic Claims and Evidence Passages.")
        }

        val rawResponses = if (captureRawResponses) mutableMapOf<UUID, ByteArray>() else null
        val tokenUsage = if (captureRawResponses) mutableMapOf<UUID, LayaEvaluationProvider.TokenUsage>() else null
        return try {
            val judgements = request.evidencePassages.map { passage ->
                evaluatePassage(request.atomicClaim.text, passage, rawResponses, tokenUsage)
            }
            LayaEvaluationProvider.Evaluation(
                result = SemanticJudgementResult(judgements),
                rawResponseBytesByPassageId = rawResponses?.toMap().orEmpty(),
                tokenUsageByPassageId = tokenUsage?.toMap().orEmpty(),
            )
        } catch (exception: LayaSystemOneProviderException) {
            throw exception
        } catch (_: Exception) {
            throw LayaSystemOneProviderException("Laya System One request failed.")
        }
    }

    private fun evaluatePassage(
        claim: String,
        passage: EvidencePassageForJudgement,
        rawResponses: MutableMap<UUID, ByteArray>?,
        tokenUsage: MutableMap<UUID, LayaEvaluationProvider.TokenUsage>?,
    ): EvidenceJudgement {
        val preparedRequest = createRequest(claim, passage)
        executionService?.captureCurrentSystemOneRequest(preparedRequest.body)
        val response = sendRequest(preparedRequest.request)
        val body = response.body()
        executionService?.captureCurrentSystemOneResponse(body)
        if (body.size > LayaSystemOneSettings.MAX_RESPONSE_BYTES) {
            throw LayaSystemOneProviderException("Laya System One response exceeded the configured response limit.")
        }
        if (response.statusCode() !in 200..299) {
            val rejection = if (response.statusCode() == 422) rejectionDetails(body) else null
            val failureReasonCode = rejection?.failureReasonCode
            val message = when (failureReasonCode) {
                LayaSystemOneProviderException.CONTEXT_LIMIT_EXCEEDED ->
                    "Laya System One rejected a request that exceeds its context limit."
                LayaSystemOneProviderException.REQUEST_REJECTED ->
                    "Laya System One rejected the request before producing a judgement."
                else -> "Laya System One returned HTTP ${response.statusCode()}."
            }
            throw LayaSystemOneProviderException(message, failureReasonCode, rejection?.measuredSequenceTokenCounts.orEmpty())
        }
        val mapped = mapResponse(body, passage.id, requireTokenUsage = tokenUsage != null)
        rawResponses?.put(passage.id, body.copyOf())
        tokenUsage?.put(
            passage.id,
            mapped.tokenUsage ?: throw LayaSystemOneProviderException("Laya System One evaluation response has no token usage metadata."),
        )
        return mapped.judgement
    }

    private fun rejectionDetails(body: ByteArray): RejectionDetails {
        val response = runCatching { objectMapper.readTree(body) }.getOrNull()
        return if (containsContextLimitRejection(response)) {
            RejectionDetails(
                failureReasonCode = LayaSystemOneProviderException.CONTEXT_LIMIT_EXCEEDED,
                measuredSequenceTokenCounts = measuredSequenceTokenCounts(response),
            )
        } else {
            RejectionDetails(LayaSystemOneProviderException.REQUEST_REJECTED, emptyList())
        }
    }

    private fun measuredSequenceTokenCounts(node: JsonNode?): List<Int> {
        if (node == null) return emptyList()
        val counts = if (node.isTextual) {
            val encodedCounts = MEASURED_SEQUENCE_TOKEN_COUNTS.find(node.asText())?.groupValues?.getOrNull(1)
                ?: return emptyList()
            encodedCounts.split(',').mapNotNull { it.trim().toIntOrNull() }
        } else {
            node.elements().asSequence().flatMap { child -> measuredSequenceTokenCounts(child).asSequence() }.toList()
        }
        return counts.takeIf { it.size == QUESTION_SEQUENCE_COUNT && it.all { count -> count > 0 } }.orEmpty()
    }

    private fun containsContextLimitRejection(node: JsonNode?): Boolean {
        if (node == null) return false
        if (node.isTextual) return CONTEXT_LIMIT_REJECTION_DETAIL in node.asText()
        return node.elements().asSequence().any(::containsContextLimitRejection)
    }

    private fun createRequest(
        claim: String,
        passage: EvidencePassageForJudgement,
        preflight: Boolean = false,
    ): PreparedRequest {
        val endpoint = settings.endpointUri ?: throw LayaSystemOneProviderException("Laya System One endpoint is unavailable.")
        val requestUri = if (preflight) URI.create("${endpoint.toASCIIString()}/preflight") else endpoint
        val state = linkedMapOf("claim" to claim, "evidence" to passage.text)
        passage.sectionHeading?.takeIf(String::isNotBlank)?.let { state["section"] = it }
        val requestBody = try {
            objectMapper.writeValueAsBytes(
                linkedMapOf(
                    "model" to LayaSystemOneSettings.REQUEST_MODEL_ALIAS,
                    "state" to state,
                    "questions" to questions(),
                ),
            )
        } catch (_: IOException) {
            throw LayaSystemOneProviderException("Laya System One request could not be encoded.")
        }
        val request = HttpRequest.newBuilder(requestUri)
            .timeout(Duration.ofMillis(settings.requestTimeoutMillis))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer ${settings.apiKey}")
            .POST(HttpRequest.BodyPublishers.ofByteArray(requestBody))
            .build()
        return PreparedRequest(request, requestBody)
    }

    private data class PreparedRequest(val request: HttpRequest, val body: ByteArray)

    private fun requireConfigured() {
        if (!settings.isSelectable || settings.endpointUri == null) {
            throw LayaSystemOneProviderException("Laya System One is not configured as an authenticated local provider.")
        }
    }

    private fun sendRequest(request: HttpRequest): HttpResponse<ByteArray> = try {
        httpClient.send(request, settings.requestTimeoutMillis, LayaSystemOneSettings.MAX_RESPONSE_BYTES)
    } catch (failure: BoundedHttpRequestException) {
        val message = when (failure.reason) {
            BoundedHttpRequestException.Reason.TIMEOUT -> "Laya System One request timed out."
            BoundedHttpRequestException.Reason.UNAVAILABLE -> "Laya System One endpoint is unavailable."
            BoundedHttpRequestException.Reason.INTERRUPTED -> "Laya System One request was interrupted."
        }
        throw LayaSystemOneProviderException(message)
    }

    private fun mapResponse(
        body: ByteArray,
        evidenceCandidateId: UUID,
        requireTokenUsage: Boolean,
    ): MappedResponse {
        val response = try {
            objectMapper.readTree(body)
        } catch (_: IOException) {
            throw LayaSystemOneProviderException("Laya System One returned a malformed response.")
        }
        if (response == null || !response.isObject || response.path("model").asText() != LAYA_RUNTIME_MODEL_NAME ||
            response.path("routing").path("model").asText() != LayaSystemOneSettings.REQUEST_MODEL_ALIAS
        ) {
            throw LayaSystemOneProviderException("Laya System One response does not identify the pinned runtime and checkpoint.")
        }
        val tokenUsage = if (requireTokenUsage) validateUsage(response.get("usage")) else null
        val answers = response.get("answers")
        if (answers == null || !answers.isObject || answers.fieldNames().asSequence().toSet() != EXPECTED_ANSWER_IDS) {
            throw LayaSystemOneProviderException("Laya System One response does not contain the supported judgement answers.")
        }

        val judgementAnswer = validateChoice(answers.get(JUDGEMENT_QUESTION), JUDGEMENT_QUESTION, JUDGEMENT_VALUES)
        val roleAnswer = validateChoice(answers.get(ROLE_QUESTION), ROLE_QUESTION, ROLE_VALUES)
        val judgement = EvidenceJudgementKind.valueOf(judgementAnswer.path("choice").asText())
        val evidenceRole = EvidenceRole.valueOf(roleAnswer.path("choice").asText())
        val judgementResult = EvidenceJudgement(
            evidenceCandidateId = evidenceCandidateId,
            judgement = judgement,
            evidenceRole = evidenceRole,
            confidence = answerConfidence(judgementAnswer, JUDGEMENT_QUESTION),
            directness = normalizedScore(answers.get(DIRECTNESS_QUESTION), DIRECTNESS_QUESTION, DIRECTNESS_LEVELS),
            claimScopeMatch = normalizedScore(answers.get(CLAIM_SCOPE_QUESTION), CLAIM_SCOPE_QUESTION, CLAIM_SCOPE_LEVELS),
            studyDesignQuality = normalizedScore(answers.get(STUDY_DESIGN_QUESTION), STUDY_DESIGN_QUESTION, STUDY_DESIGN_LEVELS),
            relevance = normalizedScore(answers.get(RELEVANCE_QUESTION), RELEVANCE_QUESTION, RELEVANCE_LEVELS),
        )
        return MappedResponse(judgementResult, tokenUsage)
    }

    private fun validateUsage(usage: JsonNode?): LayaEvaluationProvider.TokenUsage {
        if (usage == null || !usage.isObject || !nonNegativeInteger(usage.get("input_tokens")) ||
            usage.path("input_tokens").longValue() == 0L || !nonNegativeInteger(usage.get("output_tokens"))
        ) {
            throw LayaSystemOneProviderException("Laya System One response contains unsupported usage metadata.")
        }
        return LayaEvaluationProvider.TokenUsage(
            inputTokens = usage.path("input_tokens").longValue(),
            outputTokens = usage.path("output_tokens").longValue(),
        )
    }

    private fun validateChoice(answer: JsonNode?, questionId: String, expectedValues: Set<String>): JsonNode {
        if (answer == null || !answer.isObject || answer.path("type").asText() != "choice" ||
            !answer.path("choice").isTextual || answer.path("choice").asText() !in expectedValues
        ) {
            throw LayaSystemOneProviderException("Laya System One returned an unsupported choice answer.")
        }
        validateProbabilities(answer.get("probabilities"), expectedValues)
        validateConfidence(answer, questionId)
        return answer
    }

    private fun normalizedScore(answer: JsonNode?, questionId: String, levels: List<String>): Double {
        val expectedKeys = SCORE_LEVEL_LABELS.indices.map(Int::toString).toSet()
        if (answer == null || !answer.isObject || answer.path("type").asText() != "score" ||
            !answer.path("score").isNumber || !legendMatches(answer.get("legend"), SCORE_LEVEL_LABELS)
        ) {
            throw LayaSystemOneProviderException("Laya System One returned an unsupported score answer.")
        }
        validateProbabilities(answer.get("probabilities"), expectedKeys)
        validateConfidence(answer, questionId)
        val score = answer.path("score").doubleValue()
        if (!score.isFinite() || score !in 0.0..(levels.lastIndex.toDouble())) {
            throw LayaSystemOneProviderException("Laya System One returned a score outside the supported range.")
        }
        return score / levels.lastIndex
    }

    private fun legendMatches(legend: JsonNode?, levels: List<String>): Boolean {
        if (legend == null || !legend.isObject || legend.fieldNames().asSequence().toSet() != levels.indices.map(Int::toString).toSet()) {
            return false
        }
        return levels.indices.all { index -> legend.path(index.toString()).asText() == levels[index] }
    }

    private fun validateProbabilities(probabilities: JsonNode?, expectedKeys: Set<String>) {
        if (probabilities == null || !probabilities.isObject ||
            probabilities.fieldNames().asSequence().toSet() != expectedKeys
        ) {
            throw LayaSystemOneProviderException("Laya System One returned an incomplete probability distribution.")
        }
        val values = probabilities.properties().asSequence().map { (_, value) ->
            if (!value.isNumber || !value.doubleValue().isFinite() || value.doubleValue() !in 0.0..1.0) {
                throw LayaSystemOneProviderException("Laya System One returned an invalid probability value.")
            }
            value.doubleValue()
        }.toList()
        if (kotlin.math.abs(values.sum() - 1.0) > PROBABILITY_SUM_TOLERANCE) {
            throw LayaSystemOneProviderException("Laya System One returned an unnormalized probability distribution.")
        }
    }

    private fun validateConfidence(answer: JsonNode, questionId: String) {
        val confidence = answer.get("confidence")
        if (confidence == null || !confidence.isNumber || !confidence.doubleValue().isFinite() ||
            confidence.doubleValue() !in 0.0..1.0
        ) {
            throw LayaSystemOneProviderException("Laya System One returned an invalid $questionId confidence score.")
        }
    }

    private fun answerConfidence(answer: JsonNode, questionId: String): Double {
        val answerConfidence = answer.get("answer_confidence")
        if (answerConfidence == null || !answerConfidence.isNumber || !answerConfidence.doubleValue().isFinite() ||
            answerConfidence.doubleValue() !in 0.0..1.0
        ) {
            throw LayaSystemOneProviderException("Laya System One returned an invalid $questionId answer confidence score.")
        }
        return answerConfidence.doubleValue()
    }

    private fun nonNegativeInteger(value: JsonNode?): Boolean =
        value != null && value.isIntegralNumber && value.canConvertToLong() && value.longValue() >= 0

    private fun questions(): Map<String, Any> = SystemOneEvidenceJudgementContract.layaQuestions()

    private data class MappedResponse(
        val judgement: EvidenceJudgement,
        val tokenUsage: LayaEvaluationProvider.TokenUsage?,
    )

    private data class RejectionDetails(
        val failureReasonCode: String,
        val measuredSequenceTokenCounts: List<Int>,
    )

    companion object {
        const val PROVIDER_ID = LayaSystemOneSettings.PROVIDER_ID
        const val PROVIDER_VERSION = LayaSystemOneSettings.PROVIDER_VERSION
        const val PINNED_MODEL_ID = LayaSystemOneSettings.PINNED_MODEL_ID

        private const val CONNECT_TIMEOUT_SECONDS = 5L
        private const val LAYA_RUNTIME_MODEL_NAME = "laya-rl-agent"
        private const val CONTEXT_LIMIT_REJECTION_DETAIL = "exceed the 1024-token context limit"
        private val MEASURED_SEQUENCE_TOKEN_COUNTS = Regex("Measured complete-sequence token counts: ([0-9, ]+)\\.")
        private const val JUDGEMENT_QUESTION = SystemOneEvidenceJudgementContract.JUDGEMENT_QUESTION
        private const val ROLE_QUESTION = SystemOneEvidenceJudgementContract.ROLE_QUESTION
        private const val DIRECTNESS_QUESTION = SystemOneEvidenceJudgementContract.DIRECTNESS_QUESTION
        private const val CLAIM_SCOPE_QUESTION = SystemOneEvidenceJudgementContract.CLAIM_SCOPE_QUESTION
        private const val STUDY_DESIGN_QUESTION = SystemOneEvidenceJudgementContract.STUDY_DESIGN_QUESTION
        private const val RELEVANCE_QUESTION = SystemOneEvidenceJudgementContract.RELEVANCE_QUESTION
        private const val PROBABILITY_SUM_TOLERANCE = SystemOneEvidenceJudgementContract.PROBABILITY_SUM_TOLERANCE
        private const val QUESTION_SEQUENCE_COUNT = 6

        private val SCORE_LEVEL_LABELS = SystemOneEvidenceJudgementContract.SCORE_LEVEL_LABELS
        private val JUDGEMENT_VALUES = SystemOneEvidenceJudgementContract.JUDGEMENT_VALUES
        private val ROLE_VALUES = SystemOneEvidenceJudgementContract.ROLE_VALUES
        private val EXPECTED_ANSWER_IDS = SystemOneEvidenceJudgementContract.EXPECTED_ANSWER_IDS
        private val DIRECTNESS_LEVELS = SystemOneEvidenceJudgementContract.DIRECTNESS_LEVELS
        private val CLAIM_SCOPE_LEVELS = SystemOneEvidenceJudgementContract.CLAIM_SCOPE_LEVELS
        private val STUDY_DESIGN_LEVELS = SystemOneEvidenceJudgementContract.STUDY_DESIGN_LEVELS
        private val RELEVANCE_LEVELS = SystemOneEvidenceJudgementContract.RELEVANCE_LEVELS
    }
}
