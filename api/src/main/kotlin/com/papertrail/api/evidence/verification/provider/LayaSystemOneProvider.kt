package com.papertrail.api.evidence.verification.provider

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.papertrail.api.evidence.verification.domain.EvidenceJudgement
import com.papertrail.api.evidence.verification.domain.EvidenceJudgementKind
import com.papertrail.api.evidence.verification.domain.EvidencePassageForJudgement
import com.papertrail.api.evidence.verification.domain.EvidenceRole
import com.papertrail.api.evidence.verification.domain.SemanticJudgementRequest
import com.papertrail.api.evidence.verification.domain.SemanticJudgementResult
import org.springframework.stereotype.Component
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.nio.ByteBuffer
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ExecutionException
import java.util.concurrent.Flow
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

@Component
class LayaSystemOneProvider(
    private val settings: LayaSystemOneSettings,
    private val objectMapper: ObjectMapper,
) : SystemOneProvider {
    override val providerId = LayaSystemOneSettings.PROVIDER_ID
    override val version = LayaSystemOneSettings.PROVIDER_VERSION
    override val modelId = LayaSystemOneSettings.PINNED_MODEL_ID

    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    override fun evaluate(request: SemanticJudgementRequest): SemanticJudgementResult {
        if (request.evidencePassages.isEmpty()) return SemanticJudgementResult(emptyList())
        if (!settings.isSelectable || settings.endpointUri == null) {
            throw LayaSystemOneProviderException("Laya System One is not configured as an authenticated local provider.")
        }
        if (request.atomicClaim.text.isBlank() || request.evidencePassages.any { it.text.isBlank() }) {
            throw LayaSystemOneProviderException("Laya System One requires non-empty Atomic Claims and Evidence Passages.")
        }

        return try {
            SemanticJudgementResult(request.evidencePassages.map { passage ->
                evaluatePassage(request.atomicClaim.text, passage)
            })
        } catch (exception: LayaSystemOneProviderException) {
            throw exception
        } catch (_: Exception) {
            throw LayaSystemOneProviderException("Laya System One request failed.")
        }
    }

    private fun evaluatePassage(claim: String, passage: EvidencePassageForJudgement): EvidenceJudgement {
        val response = sendRequest(createRequest(claim, passage))
        if (response.statusCode() !in 200..299) {
            throw LayaSystemOneProviderException("Laya System One returned HTTP ${response.statusCode()}.")
        }
        val body = response.body()
        if (body.size > LayaSystemOneSettings.MAX_RESPONSE_BYTES) {
            throw LayaSystemOneProviderException("Laya System One response exceeded the configured response limit.")
        }
        return mapResponse(body, passage.id)
    }

    private fun createRequest(claim: String, passage: EvidencePassageForJudgement): HttpRequest {
        val endpoint = settings.endpointUri ?: throw LayaSystemOneProviderException("Laya System One endpoint is unavailable.")
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
        return HttpRequest.newBuilder(endpoint)
            .timeout(Duration.ofMillis(settings.requestTimeoutMillis))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer ${settings.apiKey}")
            .POST(HttpRequest.BodyPublishers.ofByteArray(requestBody))
            .build()
    }

    private fun sendRequest(request: HttpRequest): HttpResponse<ByteArray> {
        val responseFuture = httpClient.sendAsync(
            request,
            boundedResponseBodyHandler(LayaSystemOneSettings.MAX_RESPONSE_BYTES),
        )
        return try {
            responseFuture.get(settings.requestTimeoutMillis, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            responseFuture.cancel(true)
            throw LayaSystemOneProviderException("Laya System One request timed out.")
        } catch (failure: ExecutionException) {
            if (failure.cause is HttpTimeoutException) {
                throw LayaSystemOneProviderException("Laya System One request timed out.")
            }
            throw LayaSystemOneProviderException("Laya System One endpoint is unavailable.")
        } catch (_: InterruptedException) {
            responseFuture.cancel(true)
            Thread.currentThread().interrupt()
            throw LayaSystemOneProviderException("Laya System One request was interrupted.")
        }
    }

    private fun boundedResponseBodyHandler(maxBytes: Int): HttpResponse.BodyHandler<ByteArray> =
        HttpResponse.BodyHandler {
            object : HttpResponse.BodySubscriber<ByteArray> {
                private val body = ByteArrayOutputStream()
                private val result = CompletableFuture<ByteArray>()
                private lateinit var subscription: Flow.Subscription

                override fun getBody(): CompletionStage<ByteArray> = result

                override fun onSubscribe(subscription: Flow.Subscription) {
                    this.subscription = subscription
                    subscription.request(1)
                }

                override fun onNext(items: List<ByteBuffer>) {
                    for (buffer in items) {
                        if (buffer.remaining() > maxBytes - body.size()) {
                            subscription.cancel()
                            result.complete(ByteArray(maxBytes + 1))
                            return
                        }
                        val bytes = ByteArray(buffer.remaining())
                        buffer.get(bytes)
                        body.write(bytes, 0, bytes.size)
                    }
                    subscription.request(1)
                }

                override fun onError(throwable: Throwable) {
                    result.completeExceptionally(throwable)
                }

                override fun onComplete() {
                    result.complete(body.toByteArray())
                }
            }
        }

    private fun mapResponse(body: ByteArray, evidenceCandidateId: UUID): EvidenceJudgement {
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
        validateUsage(response.get("usage"))
        val answers = response.get("answers")
        if (answers == null || !answers.isObject || answers.fieldNames().asSequence().toSet() != EXPECTED_ANSWER_IDS) {
            throw LayaSystemOneProviderException("Laya System One response does not contain the supported judgement answers.")
        }

        val judgementAnswer = validateChoice(answers.get(JUDGEMENT_QUESTION), JUDGEMENT_QUESTION, JUDGEMENT_VALUES)
        val roleAnswer = validateChoice(answers.get(ROLE_QUESTION), ROLE_QUESTION, ROLE_VALUES)
        val judgement = EvidenceJudgementKind.valueOf(judgementAnswer.path("choice").asText())
        val evidenceRole = EvidenceRole.valueOf(roleAnswer.path("choice").asText())
        return EvidenceJudgement(
            evidenceCandidateId = evidenceCandidateId,
            judgement = judgement,
            evidenceRole = evidenceRole,
            confidence = answerConfidence(judgementAnswer, JUDGEMENT_QUESTION),
            directness = normalizedScore(answers.get(DIRECTNESS_QUESTION), DIRECTNESS_QUESTION, DIRECTNESS_LEVELS),
            claimScopeMatch = normalizedScore(answers.get(CLAIM_SCOPE_QUESTION), CLAIM_SCOPE_QUESTION, CLAIM_SCOPE_LEVELS),
            studyDesignQuality = normalizedScore(answers.get(STUDY_DESIGN_QUESTION), STUDY_DESIGN_QUESTION, STUDY_DESIGN_LEVELS),
            relevance = normalizedScore(answers.get(RELEVANCE_QUESTION), RELEVANCE_QUESTION, RELEVANCE_LEVELS),
        )
    }

    private fun validateUsage(usage: JsonNode?) {
        if (usage == null || !usage.isObject || !nonNegativeInteger(usage.get("input_tokens")) ||
            !nonNegativeInteger(usage.get("output_tokens"))
        ) {
            throw LayaSystemOneProviderException("Laya System One response contains unsupported usage metadata.")
        }
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

    private fun questions(): Map<String, Any> = linkedMapOf(
        JUDGEMENT_QUESTION to mapOf(
            "type" to "choice",
            "instructions" to "How does the evidence passage relate to the atomic claim, considering every material qualifier?",
            "criteria" to linkedMapOf(
                "DIRECT_SUPPORT" to "The passage directly reports evidence supporting the claim and its material qualifiers.",
                "PARTIAL_SUPPORT" to "The passage supports only part of the claim or misses a material qualifier.",
                "CONTRADICTS" to "The passage reports evidence that materially conflicts with the claim.",
                "UNRELATED" to "The passage has no material bearing on the claim.",
                "INSUFFICIENT" to "The passage is ambiguous or does not permit a supported judgement.",
            ),
        ),
        ROLE_QUESTION to mapOf(
            "type" to "choice",
            "instructions" to "What is the evidentiary role of this passage within the cited paper?",
            "criteria" to linkedMapOf(
                "PRIMARY_FINDING" to "The cited paper's own methods, data, or results report this finding.",
                "AUTHOR_SYNTHESIS" to "The cited paper's authors interpret, summarize, or synthesize evidence across works.",
                "SECONDARY_REPORT" to "The passage attributes a finding to another cited work rather than reporting this paper's own result.",
            ),
        ),
        DIRECTNESS_QUESTION to scoreQuestion(
            "How directly does the passage answer the atomic claim?",
            DIRECTNESS_LEVELS,
        ),
        CLAIM_SCOPE_QUESTION to scoreQuestion(
            "How closely does the passage match the claim's population, conditions, outcome, and other material qualifiers?",
            CLAIM_SCOPE_LEVELS,
        ),
        STUDY_DESIGN_QUESTION to scoreQuestion(
            "How strong is the study design described in the passage for assessing this claim?",
            STUDY_DESIGN_LEVELS,
        ),
        RELEVANCE_QUESTION to scoreQuestion(
            "How relevant is the passage to the atomic claim?",
            RELEVANCE_LEVELS,
        ),
    )

    private fun scoreQuestion(instructions: String, levels: List<String>): Map<String, Any> = mapOf(
        "type" to "score",
        "instructions" to instructions + " Use this ordered scale: " + levels.mapIndexed { index, level -> "$index=$level" }.joinToString("; "),
        "criteria" to SCORE_LEVEL_LABELS,
    )

    companion object {
        const val PROVIDER_ID = LayaSystemOneSettings.PROVIDER_ID
        const val PROVIDER_VERSION = LayaSystemOneSettings.PROVIDER_VERSION
        const val PINNED_MODEL_ID = LayaSystemOneSettings.PINNED_MODEL_ID

        private const val CONNECT_TIMEOUT_SECONDS = 5L
        private const val LAYA_RUNTIME_MODEL_NAME = "laya-rl-agent"
        private const val JUDGEMENT_QUESTION = "judgement"
        private const val ROLE_QUESTION = "evidence_role"
        private const val DIRECTNESS_QUESTION = "directness"
        private const val CLAIM_SCOPE_QUESTION = "claim_scope_match"
        private const val STUDY_DESIGN_QUESTION = "study_design_quality"
        private const val RELEVANCE_QUESTION = "relevance"
        private const val PROBABILITY_SUM_TOLERANCE = 0.02

        private val SCORE_LEVEL_LABELS = listOf("none", "low", "moderate", "high", "complete")
        private val JUDGEMENT_VALUES = setOf(
            EvidenceJudgementKind.DIRECT_SUPPORT.name,
            EvidenceJudgementKind.PARTIAL_SUPPORT.name,
            EvidenceJudgementKind.CONTRADICTS.name,
            EvidenceJudgementKind.UNRELATED.name,
            EvidenceJudgementKind.INSUFFICIENT.name,
        )
        private val ROLE_VALUES = setOf(
            EvidenceRole.PRIMARY_FINDING.name,
            EvidenceRole.AUTHOR_SYNTHESIS.name,
            EvidenceRole.SECONDARY_REPORT.name,
        )
        private val EXPECTED_ANSWER_IDS = setOf(
            JUDGEMENT_QUESTION,
            ROLE_QUESTION,
            DIRECTNESS_QUESTION,
            CLAIM_SCOPE_QUESTION,
            STUDY_DESIGN_QUESTION,
            RELEVANCE_QUESTION,
        )
        private val DIRECTNESS_LEVELS = listOf(
            "No evidence addresses the claim.",
            "Only an indirect or weak connection is present.",
            "The passage is relevant but does not directly answer the claim.",
            "The passage directly addresses most of the claim.",
            "The passage directly reports evidence for the complete claim.",
        )
        private val CLAIM_SCOPE_LEVELS = listOf(
            "The population, conditions, or outcome do not match.",
            "A major material qualifier is missing or conflicts.",
            "The core claim matches, but at least one material qualifier is uncertain.",
            "The claim scope and nearly all material qualifiers match.",
            "The population, conditions, outcome, and material qualifiers match.",
        )
        private val STUDY_DESIGN_LEVELS = listOf(
            "No study design or method is described.",
            "The described design provides very weak evidence for this claim.",
            "The design provides limited or observational evidence.",
            "The design provides reasonably strong evidence for this claim.",
            "The design is rigorous and directly suited to assess this claim.",
        )
        private val RELEVANCE_LEVELS = listOf(
            "The passage is unrelated to the claim.",
            "The passage has only a slight topical connection.",
            "The passage is relevant but only partly addresses the claim.",
            "The passage is strongly relevant to the claim.",
            "The passage is directly and fully relevant to the claim.",
        )
    }
}
