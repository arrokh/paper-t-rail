package com.papertrail.api.evidence.verification.provider

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.papertrail.api.analysis.execution.AnalysisRunExecutionService
import com.papertrail.api.evidence.verification.domain.EvidenceJudgement
import com.papertrail.api.evidence.verification.domain.EvidenceJudgementKind
import com.papertrail.api.evidence.verification.domain.EvidencePassageForJudgement
import com.papertrail.api.evidence.verification.domain.EvidenceRole
import com.papertrail.api.evidence.verification.domain.SemanticJudgementRequest
import com.papertrail.api.evidence.verification.domain.SemanticJudgementResult
import com.papertrail.api.infrastructure.http.BoundedHttpClient
import com.papertrail.api.infrastructure.http.BoundedHttpRequestException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.io.IOException
import java.net.http.HttpRequest
import java.time.Duration
import java.util.UUID
import kotlin.math.abs

@Component
class JevSystemOneProvider(
    private val settings: JevSystemOneSettings,
    private val objectMapper: ObjectMapper,
    private val executionService: AnalysisRunExecutionService? = null,
) : SystemOneProvider {
    override val providerId = JevSystemOneSettings.PROVIDER_ID
    override val version = JevSystemOneSettings.PROVIDER_VERSION
    override val modelId: String = settings.modelId

    private val httpClient = BoundedHttpClient(CONNECT_TIMEOUT_SECONDS)
    private val logger = LoggerFactory.getLogger(JevSystemOneProvider::class.java)

    override fun evaluate(request: SemanticJudgementRequest): SemanticJudgementResult {
        if (request.evidencePassages.isEmpty()) return SemanticJudgementResult(emptyList())
        requireConfigured()
        if (request.atomicClaim.text.isBlank() || request.evidencePassages.any { it.text.isBlank() }) {
            throw providerFailure(
                "Jev System One requires non-empty Atomic Claims and Evidence Passages.",
                JevSystemOneProviderException.INVALID_INPUT,
            )
        }

        return try {
            SemanticJudgementResult(
                request.evidencePassages.mapIndexed { index, passage ->
                    evaluatePassage(
                        atomicClaimId = request.atomicClaim.id,
                        claim = request.atomicClaim.text,
                        passage = passage,
                        passageIndex = index + 1,
                        passageCount = request.evidencePassages.size,
                    )
                },
            )
        } catch (failure: JevSystemOneProviderException) {
            throw failure
        } catch (_: Exception) {
            throw providerFailure("Jev System One request failed.")
        }
    }

    private fun evaluatePassage(
        atomicClaimId: UUID,
        claim: String,
        passage: EvidencePassageForJudgement,
        passageIndex: Int,
        passageCount: Int,
    ): EvidenceJudgement {
        val preparedRequest = createRequest(claim, passage)
        executionService?.captureCurrentSystemOneRequest(preparedRequest.body)
        val request = preparedRequest.request
        val providerCallId = UUID.randomUUID()
        val startedAtNanos = System.nanoTime()
        logger.atInfo()
            .addKeyValue("providerCallId", providerCallId)
            .addKeyValue("providerId", providerId)
            .addKeyValue("requestedModelId", modelId)
            .addKeyValue("atomicClaimId", atomicClaimId)
            .addKeyValue("evidenceCandidateId", passage.id)
            .addKeyValue("passageIndex", passageIndex)
            .addKeyValue("passageCount", passageCount)
            .log("System One provider request started")

        val response = try {
            httpClient.send(request, settings.requestTimeoutMillis, JevSystemOneSettings.MAX_RESPONSE_BYTES)
        } catch (failure: BoundedHttpRequestException) {
            val (message, failureReasonCode) = when (failure.reason) {
                BoundedHttpRequestException.Reason.TIMEOUT ->
                    "Jev System One request timed out." to JevSystemOneProviderException.TIMEOUT
                BoundedHttpRequestException.Reason.UNAVAILABLE ->
                    "Jev System One endpoint is unavailable." to JevSystemOneProviderException.UNAVAILABLE
                BoundedHttpRequestException.Reason.INTERRUPTED ->
                    "Jev System One request was interrupted." to JevSystemOneProviderException.INTERRUPTED
            }
            val providerException = providerFailure(message, failureReasonCode, retryable = true)
            logProviderCallFailure(
                providerCallId = providerCallId,
                atomicClaimId = atomicClaimId,
                passage = passage,
                failureReasonCode = failureReasonCode,
                errorType = failure.javaClass.simpleName,
                startedAtNanos = startedAtNanos,
            )
            throw providerException
        } catch (failure: Exception) {
            val providerException = providerFailure("Jev System One request failed.")
            logProviderCallFailure(
                providerCallId = providerCallId,
                atomicClaimId = atomicClaimId,
                passage = passage,
                failureReasonCode = JevSystemOneProviderException.PROVIDER_ERROR,
                errorType = failure.javaClass.simpleName,
                startedAtNanos = startedAtNanos,
            )
            throw providerException
        }
        val responseBodyBytes = response.body().size
        executionService?.captureCurrentJevResponse(response.body())
        logger.atInfo()
            .addKeyValue("providerCallId", providerCallId)
            .addKeyValue("providerId", providerId)
            .addKeyValue("requestedModelId", modelId)
            .addKeyValue("evidenceCandidateId", passage.id)
            .addKeyValue("httpStatus", response.statusCode())
            .addKeyValue("responseBodyBytes", responseBodyBytes)
            .addKeyValue("durationMillis", elapsedMillis(startedAtNanos))
            .log("System One provider response received")

        if (responseBodyBytes > JevSystemOneSettings.MAX_RESPONSE_BYTES) {
            val failureReasonCode = JevSystemOneProviderException.RESPONSE_TOO_LARGE
            logProviderCallFailure(
                providerCallId = providerCallId,
                atomicClaimId = atomicClaimId,
                passage = passage,
                failureReasonCode = failureReasonCode,
                httpStatus = response.statusCode(),
                responseBodyBytes = responseBodyBytes,
                errorType = JevSystemOneProviderException::class.java.simpleName,
                startedAtNanos = startedAtNanos,
            )
            throw providerFailure(
                "Jev System One response exceeded the configured response limit.",
                failureReasonCode,
            )
        }
        if (response.statusCode() !in 200..299) {
            val retryable = isRetryableHttpStatus(response.statusCode())
            val failureReasonCode = JevSystemOneProviderException.httpFailureReasonCode(response.statusCode())
            logProviderCallFailure(
                providerCallId = providerCallId,
                atomicClaimId = atomicClaimId,
                passage = passage,
                failureReasonCode = failureReasonCode,
                httpStatus = response.statusCode(),
                responseBodyBytes = responseBodyBytes,
                errorType = JevSystemOneProviderException::class.java.simpleName,
                startedAtNanos = startedAtNanos,
            )
            throw providerFailure(
                "Jev System One returned HTTP ${response.statusCode()}.",
                failureReasonCode,
                retryable,
            )
        }
        return try {
            mapResponse(response.body(), passage.id).also {
                logger.atInfo()
                    .addKeyValue("providerCallId", providerCallId)
                    .addKeyValue("providerId", providerId)
                    .addKeyValue("requestedModelId", modelId)
                    .addKeyValue("evidenceCandidateId", passage.id)
                    .addKeyValue("durationMillis", elapsedMillis(startedAtNanos))
                    .log("System One provider response mapped")
            }
        } catch (failure: Exception) {
            val providerException = failure as? JevSystemOneProviderException
                ?: providerFailure("Jev System One response could not be mapped.")
            logProviderCallFailure(
                providerCallId = providerCallId,
                atomicClaimId = atomicClaimId,
                passage = passage,
                failureReasonCode = providerException.failureReasonCode ?: JevSystemOneProviderException.PROVIDER_ERROR,
                diagnosticField = providerException.diagnosticField,
                diagnosticReasonCode = providerException.diagnosticReasonCode,
                httpStatus = response.statusCode(),
                responseBodyBytes = responseBodyBytes,
                errorType = failure.javaClass.simpleName,
                startedAtNanos = startedAtNanos,
                eventMessage = "System One provider response rejected",
            )
            throw providerException
        }
    }

    private fun logProviderCallFailure(
        providerCallId: UUID,
        atomicClaimId: UUID,
        passage: EvidencePassageForJudgement,
        failureReasonCode: String,
        errorType: String,
        startedAtNanos: Long,
        diagnosticField: String? = null,
        diagnosticReasonCode: String? = null,
        httpStatus: Int? = null,
        responseBodyBytes: Int? = null,
        eventMessage: String = "System One provider call failed",
    ) {
        val log = logger.atWarn()
            .addKeyValue("providerCallId", providerCallId)
            .addKeyValue("providerId", providerId)
            .addKeyValue("requestedModelId", modelId)
            .addKeyValue("atomicClaimId", atomicClaimId)
            .addKeyValue("evidenceCandidateId", passage.id)
            .addKeyValue("failureReasonCode", failureReasonCode)
            .addKeyValue("errorType", errorType)
            .addKeyValue("durationMillis", elapsedMillis(startedAtNanos))
        diagnosticField?.let { log.addKeyValue("diagnosticField", it) }
        diagnosticReasonCode?.let { log.addKeyValue("diagnosticReasonCode", it) }
        httpStatus?.let { log.addKeyValue("httpStatus", it) }
        responseBodyBytes?.let { log.addKeyValue("responseBodyBytes", it) }
        log.log(eventMessage)
    }

    private fun elapsedMillis(startedAtNanos: Long): Long =
        (System.nanoTime() - startedAtNanos).coerceAtLeast(0L) / NANOS_PER_MILLI

    private fun createRequest(claim: String, passage: EvidencePassageForJudgement): PreparedRequest {
        val endpoint = settings.endpointUri ?: throw providerFailure(
            "Jev System One endpoint is unavailable.",
            JevSystemOneProviderException.NOT_CONFIGURED,
        )
        val state = linkedMapOf("claim" to claim, "evidence" to passage.text)
        passage.sectionHeading?.takeIf(String::isNotBlank)?.let { state["section"] = it }
        val requestBody = try {
            objectMapper.writeValueAsBytes(
                linkedMapOf(
                    "model" to settings.modelId,
                    "state" to state,
                    "questions" to SystemOneEvidenceJudgementContract.jevQuestions(),
                ),
            )
        } catch (_: IOException) {
            throw providerFailure(
                "Jev System One request could not be encoded.",
                JevSystemOneProviderException.REQUEST_ENCODING_FAILED,
            )
        }
        val request = HttpRequest.newBuilder(endpoint)
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
            throw providerFailure(
                "Jev System One is not configured with a valid server-side API key and endpoint.",
                JevSystemOneProviderException.NOT_CONFIGURED,
            )
        }
    }

    private fun mapResponse(body: ByteArray, evidenceCandidateId: UUID): EvidenceJudgement {
        val response = try {
            objectMapper.readTree(body)
        } catch (_: IOException) {
            throw invalidResponse(
                "Jev System One returned a malformed response.",
                JevSystemOneProviderException.MALFORMED_JSON,
                "response",
            )
        }
        if (response == null || !response.isObject) {
            throw invalidResponse(
                "Jev System One returned an unsupported response envelope.",
                JevSystemOneProviderException.ENVELOPE_INVALID,
                "response",
            )
        }
        val reportedModel = response.get("model")
        if (reportedModel == null || !reportedModel.isTextual || reportedModel.asText().isBlank() ||
            reportedModel.asText().length > MAX_REPORTED_MODEL_LENGTH || reportedModel.asText().any(Char::isISOControl)
        ) {
            throw invalidResponse(
                "Jev System One response does not identify the model that produced the judgement.",
                JevSystemOneProviderException.MODEL_INVALID,
                "model",
            )
        }
        validateUsage(response.get("usage"))
        val answers = response.get("answers")
        if (answers == null || !answers.isObject ||
            answers.fieldNames().asSequence().toSet() != SystemOneEvidenceJudgementContract.EXPECTED_ANSWER_IDS
        ) {
            throw invalidResponse(
                "Jev System One response does not contain the supported judgement answers.",
                JevSystemOneProviderException.ANSWER_SET_INVALID,
                "answers",
            )
        }

        val judgementAnswer = validateChoice(
            answers.get(SystemOneEvidenceJudgementContract.JUDGEMENT_QUESTION),
            SystemOneEvidenceJudgementContract.JUDGEMENT_QUESTION,
            SystemOneEvidenceJudgementContract.JUDGEMENT_VALUES,
        )
        val roleAnswer = validateChoice(
            answers.get(SystemOneEvidenceJudgementContract.ROLE_QUESTION),
            SystemOneEvidenceJudgementContract.ROLE_QUESTION,
            SystemOneEvidenceJudgementContract.ROLE_VALUES,
        )
        return EvidenceJudgement(
            evidenceCandidateId = evidenceCandidateId,
            judgement = EvidenceJudgementKind.valueOf(judgementAnswer.path("choice").asText()),
            evidenceRole = EvidenceRole.valueOf(roleAnswer.path("choice").asText()),
            confidence = judgementAnswer.path("confidence").doubleValue(),
            directness = normalizedScore(
                answers.get(SystemOneEvidenceJudgementContract.DIRECTNESS_QUESTION),
                SystemOneEvidenceJudgementContract.DIRECTNESS_QUESTION,
                SystemOneEvidenceJudgementContract.DIRECTNESS_LEVELS,
            ),
            claimScopeMatch = normalizedScore(
                answers.get(SystemOneEvidenceJudgementContract.CLAIM_SCOPE_QUESTION),
                SystemOneEvidenceJudgementContract.CLAIM_SCOPE_QUESTION,
                SystemOneEvidenceJudgementContract.CLAIM_SCOPE_LEVELS,
            ),
            studyDesignQuality = normalizedScore(
                answers.get(SystemOneEvidenceJudgementContract.STUDY_DESIGN_QUESTION),
                SystemOneEvidenceJudgementContract.STUDY_DESIGN_QUESTION,
                SystemOneEvidenceJudgementContract.STUDY_DESIGN_LEVELS,
            ),
            relevance = normalizedScore(
                answers.get(SystemOneEvidenceJudgementContract.RELEVANCE_QUESTION),
                SystemOneEvidenceJudgementContract.RELEVANCE_QUESTION,
                SystemOneEvidenceJudgementContract.RELEVANCE_LEVELS,
            ),
            providerReportedModelId = reportedModel.asText(),
        )
    }

    private fun validateUsage(usage: JsonNode?) {
        if (usage == null || !usage.isObject || !nonNegativeInteger(usage.get("input_tokens")) ||
            !nonNegativeInteger(usage.get("output_tokens"))
        ) {
            throw invalidResponse(
                "Jev System One response contains unsupported usage metadata.",
                JevSystemOneProviderException.USAGE_INVALID,
                "usage",
            )
        }
    }

    private fun validateChoice(answer: JsonNode?, questionId: String, expectedValues: Set<String>): JsonNode {
        if (answer == null || !answer.isObject || answer.path("type").asText() != "choice" ||
            !answer.path("choice").isTextual || answer.path("choice").asText() !in expectedValues
        ) {
            throw invalidResponse(
                "Jev System One returned an unsupported $questionId choice answer.",
                JevSystemOneProviderException.CHOICE_INVALID,
                "answers.$questionId",
            )
        }
        validateProbabilities(answer.get("probabilities"), expectedValues, questionId)
        validateConfidence(answer, questionId)
        return answer
    }

    private fun normalizedScore(answer: JsonNode?, questionId: String, levels: List<String>): Double {
        val answerField = "answers.$questionId"
        if (answer == null || !answer.isObject) {
            throw invalidResponse(
                "Jev System One returned an unsupported $questionId score answer.",
                JevSystemOneProviderException.SCORE_INVALID,
                answerField,
                JevSystemOneProviderException.SCORE_ANSWER_INVALID,
            )
        }
        if (answer.path("type").asText() != "score") {
            throw invalidResponse(
                "Jev System One returned an unsupported $questionId score answer.",
                JevSystemOneProviderException.SCORE_INVALID,
                "$answerField.type",
                JevSystemOneProviderException.SCORE_TYPE_INVALID,
            )
        }
        val scoreNode = answer.get("score")
        if (scoreNode == null || !scoreNode.isNumber) {
            throw invalidResponse(
                "Jev System One returned a non-numeric $questionId score.",
                JevSystemOneProviderException.SCORE_INVALID,
                "$answerField.score",
                JevSystemOneProviderException.SCORE_VALUE_NOT_NUMERIC,
            )
        }
        if (!legendMatches(answer.get("legend"), levels)) {
            throw invalidResponse(
                "Jev System One returned an unsupported $questionId score legend.",
                JevSystemOneProviderException.SCORE_LEGEND_INVALID,
                "$answerField.legend",
            )
        }
        val expectedKeys = levels.indices.map(Int::toString).toSet()
        val probabilities = validateProbabilities(answer.get("probabilities"), expectedKeys, questionId)
        validateConfidence(answer, questionId)
        val score = scoreNode.doubleValue()
        if (!score.isFinite()) {
            throw invalidResponse(
                "Jev System One returned a non-finite $questionId score.",
                JevSystemOneProviderException.SCORE_INVALID,
                "$answerField.score",
                JevSystemOneProviderException.SCORE_VALUE_NOT_FINITE,
            )
        }
        if (score !in 0.0..levels.lastIndex.toDouble()) {
            throw invalidResponse(
                "Jev System One returned a $questionId score outside the supported range.",
                JevSystemOneProviderException.SCORE_INVALID,
                "$answerField.score",
                JevSystemOneProviderException.SCORE_VALUE_OUT_OF_RANGE,
            )
        }
        val expectedScore = probabilities.withIndex().sumOf { (index, probability) -> index * probability }
        val scoreTolerance = twoDecimalRoundingTolerance(levels.size) + FLOATING_POINT_EPSILON
        if (abs(score - expectedScore) > scoreTolerance) {
            throw invalidResponse(
                "Jev System One returned a $questionId score inconsistent with its probabilities.",
                JevSystemOneProviderException.SCORE_INVALID,
                "$answerField.score",
                JevSystemOneProviderException.SCORE_WEIGHTED_MEAN_MISMATCH,
            )
        }
        return score / levels.lastIndex
    }

    private fun legendMatches(legend: JsonNode?, levels: List<String>): Boolean {
        if (legend == null || !legend.isObject || legend.fieldNames().asSequence().toSet() != levels.indices.map(Int::toString).toSet()) {
            return false
        }
        return levels.indices.all { index ->
            legend.path(index.toString()).isTextual && legend.path(index.toString()).asText() == levels[index]
        }
    }

    private fun validateProbabilities(
        probabilities: JsonNode?,
        expectedKeys: Set<String>,
        questionId: String,
    ): List<Double> {
        val diagnosticField = "answers.$questionId.probabilities"
        if (probabilities == null || !probabilities.isObject ||
            probabilities.fieldNames().asSequence().toSet() != expectedKeys
        ) {
            throw invalidResponse(
                "Jev System One returned an incomplete probability distribution.",
                JevSystemOneProviderException.PROBABILITIES_INVALID,
                diagnosticField,
            )
        }
        val values = expectedKeys.sorted().map { key ->
            val value = probabilities.get(key)
            if (value == null || !value.isNumber || !value.doubleValue().isFinite() || value.doubleValue() !in 0.0..1.0) {
                throw invalidResponse(
                    "Jev System One returned an invalid probability value.",
                    JevSystemOneProviderException.PROBABILITIES_INVALID,
                    diagnosticField,
                )
            }
            value.doubleValue()
        }
        if (abs(values.sum() - 1.0) >
            SystemOneEvidenceJudgementContract.PROBABILITY_SUM_TOLERANCE + FLOATING_POINT_EPSILON
        ) {
            throw invalidResponse(
                "Jev System One returned an unnormalized probability distribution.",
                JevSystemOneProviderException.PROBABILITIES_INVALID,
                diagnosticField,
            )
        }
        return values
    }

    private fun validateConfidence(answer: JsonNode, questionId: String) {
        val confidence = answer.get("confidence")
        if (confidence == null || !confidence.isNumber || !confidence.doubleValue().isFinite() ||
            confidence.doubleValue() !in 0.0..1.0
        ) {
            throw invalidResponse(
                "Jev System One returned an invalid $questionId confidence score.",
                JevSystemOneProviderException.CONFIDENCE_INVALID,
                "answers.$questionId.confidence",
            )
        }
    }

    private fun nonNegativeInteger(value: JsonNode?): Boolean =
        value != null && value.isIntegralNumber && value.canConvertToLong() && value.longValue() >= 0

    private fun invalidResponse(
        message: String,
        failureReasonCode: String = JevSystemOneProviderException.INVALID_RESPONSE,
        diagnosticField: String? = null,
        diagnosticReasonCode: String? = null,
    ): JevSystemOneProviderException =
        JevSystemOneProviderException(message, failureReasonCode, diagnosticField, diagnosticReasonCode)

    private fun providerFailure(
        message: String,
        failureReasonCode: String = JevSystemOneProviderException.PROVIDER_ERROR,
        retryable: Boolean = false,
    ): JevSystemOneProviderException = JevSystemOneProviderException(
        message = message,
        failureReasonCode = failureReasonCode,
        retryable = retryable,
    )

    companion object {
        private const val CONNECT_TIMEOUT_SECONDS = 5L
        private const val MAX_REPORTED_MODEL_LENGTH = 160
        private const val TWO_DECIMAL_HALF_STEP = 0.005
        private const val FLOATING_POINT_EPSILON = 1e-9
        private const val NANOS_PER_MILLI = 1_000_000L

        private fun isRetryableHttpStatus(statusCode: Int): Boolean =
            statusCode == 408 || statusCode == 429 || statusCode in 500..599

        // Bound independently rounded two-decimal scores and probability-weighted level indices.
        private fun twoDecimalRoundingTolerance(levelCount: Int): Double =
            TWO_DECIMAL_HALF_STEP * (1 + (0 until levelCount).sum())
    }
}
