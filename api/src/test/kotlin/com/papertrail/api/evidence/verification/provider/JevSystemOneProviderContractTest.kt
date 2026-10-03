package com.papertrail.api.evidence.verification.provider

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.papertrail.api.evidence.verification.domain.AtomicClaimForJudgement
import com.papertrail.api.evidence.verification.domain.EvidenceJudgementKind
import com.papertrail.api.evidence.verification.domain.EvidencePassageForJudgement
import com.papertrail.api.evidence.verification.domain.EvidenceRole
import com.papertrail.api.evidence.verification.domain.SemanticJudgementRequest
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.slf4j.LoggerFactory
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.Executors

class JevSystemOneProviderContractTest {
    private val objectMapper = jacksonObjectMapper()

    @Test
    fun `sends the minimum typed judgement request and maps Jev output into shared judgements`() {
        TestServer(responseBody = validResponse()).use { server ->
            val settings = settings(server.baseUrl)
            val provider = JevSystemOneProvider(settings, objectMapper)
            val claimId = UUID.randomUUID()
            val evidenceId = UUID.randomUUID()
            val result = provider.evaluate(
                SemanticJudgementRequest(
                    AtomicClaimForJudgement(claimId, "The treatment reduces mortality."),
                    listOf(EvidencePassageForJudgement(
                        evidenceId,
                        "Mortality was lower in the treatment group.",
                        "Results",
                    )),
                ),
            )

            val judgement = result.evidenceJudgements.single()
            val request = objectMapper.readTree(server.requestBody)
            assertEquals("Bearer jev-server-secret", server.authorization)
            assertEquals("POST", server.method)
            assertEquals("/v1/systemone", server.path)
            assertEquals("jev-latest", request.path("model").asText())
            assertEquals(
                setOf("claim", "evidence", "section"),
                request.path("state").fieldNames().asSequence().toSet(),
            )
            assertEquals("The treatment reduces mortality.", request.path("state").path("claim").asText())
            assertEquals("Mortality was lower in the treatment group.", request.path("state").path("evidence").asText())
            assertEquals("Results", request.path("state").path("section").asText())
            assertFalse(server.requestBody.contains(claimId.toString()))
            assertFalse(server.requestBody.contains(evidenceId.toString()))
            assertEquals(
                setOf("judgement", "evidence_role", "directness", "claim_scope_match", "study_design_quality", "relevance"),
                request.path("questions").fieldNames().asSequence().toSet(),
            )
            assertEquals("choice", request.path("questions").path("judgement").path("type").asText())
            assertEquals("choice", request.path("questions").path("evidence_role").path("type").asText())
            assertEquals("score", request.path("questions").path("directness").path("type").asText())
            assertTrue(settings.toString().contains("apiKeyConfigured=true"))
            assertFalse(settings.toString().contains("jev-server-secret"))

            assertEquals(evidenceId, judgement.evidenceCandidateId)
            assertEquals(EvidenceJudgementKind.DIRECT_SUPPORT, judgement.judgement)
            assertEquals(EvidenceRole.PRIMARY_FINDING, judgement.evidenceRole)
            assertEquals(0.85, judgement.confidence)
            assertEquals(0.875, judgement.directness)
            assertEquals(0.5, judgement.claimScopeMatch)
            assertEquals(0.3125, judgement.studyDesignQuality)
            assertEquals(1.0, judgement.relevance)
            assertEquals("jev-1.13.0", judgement.providerReportedModelId)
        }
    }

    @Test
    fun `rejects malformed unsupported or unsuccessful Jev responses without retrying`() {
        val valid = validResponse()
        data class InvalidResponse(
            val status: Int,
            val body: String,
            val failureReasonCode: String,
            val diagnosticField: String? = null,
        )
        val invalidResponses = listOf(
            InvalidResponse(401, valid, "SYSTEM_ONE_HTTP_401"),
            InvalidResponse(429, valid, "SYSTEM_ONE_HTTP_429"),
            InvalidResponse(200, "not-json", "SYSTEM_ONE_RESPONSE_MALFORMED_JSON", "response"),
            InvalidResponse(200, valid.replace("\"model\":\"jev-1.13.0\",", ""), "SYSTEM_ONE_RESPONSE_MODEL_INVALID", "model"),
            InvalidResponse(200, valid.replace("\"relevance\":", "\"unknown_relevance\":"), "SYSTEM_ONE_RESPONSE_ANSWER_SET_INVALID", "answers"),
            InvalidResponse(200, valid.replace("\"confidence\":0.85", "\"confidence\":1.1"), "SYSTEM_ONE_RESPONSE_CONFIDENCE_INVALID", "answers.judgement.confidence"),
            InvalidResponse(200, valid.replace("\"DIRECT_SUPPORT\":0.85", "\"DIRECT_SUPPORT\":0.2"), "SYSTEM_ONE_RESPONSE_PROBABILITIES_INVALID", "answers.judgement.probabilities"),
            InvalidResponse(200, valid.replace("\"usage\":{\"input_tokens\":100,\"output_tokens\":30}", "\"usage\":{\"input_tokens\":\"100\",\"output_tokens\":30}"), "SYSTEM_ONE_RESPONSE_USAGE_INVALID", "usage"),
            InvalidResponse(200, valid.replace("\"legend\":{\"0\":\"No evidence addresses the claim.\"", "\"legend\":{\"0\":\"wrong\""), "SYSTEM_ONE_RESPONSE_SCORE_LEGEND_INVALID", "answers.directness.legend"),
            InvalidResponse(200, valid.replace("\"directness\":{\"type\":\"score\",\"score\":3.5", "\"directness\":{\"type\":\"score\",\"score\":2.0"), "SYSTEM_ONE_RESPONSE_SCORE_INVALID", "answers.directness.score"),
        )

        invalidResponses.forEach { invalid ->
            TestServer(status = invalid.status, responseBody = invalid.body).use { server ->
                val failure = assertThrows<JevSystemOneProviderException> {
                    provider(server.baseUrl).evaluate(request())
                }
                assertEquals(invalid.failureReasonCode, failure.failureReasonCode)
                assertEquals(invalid.diagnosticField, failure.diagnosticField)
                assertEquals(1, server.requestCount)
                assertFalse(failure.message.orEmpty().contains("private claim"))
                assertFalse(failure.message.orEmpty().contains("jev-server-secret"))
            }
        }
    }

    @Test
    fun `logs request lifecycle and response validation fields without sensitive contents`() {
        val providerLogger = LoggerFactory.getLogger(JevSystemOneProvider::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        providerLogger.addAppender(appender)
        try {
            TestServer(responseBody = validResponse().replace(
                "\"model\":\"jev-1.13.0\"",
                "\"model\":\"response-only-model-marker\"",
            )).use { server ->
                val claimId = UUID.randomUUID()
                val passageId = UUID.randomUUID()
                provider(server.baseUrl).evaluate(
                    SemanticJudgementRequest(
                        AtomicClaimForJudgement(claimId, "private claim text"),
                        listOf(EvidencePassageForJudgement(passageId, "private evidence text", null)),
                    ),
                )
            }

            TestServer(responseBody = validResponse().replace(
                "\"usage\":{\"input_tokens\":100,\"output_tokens\":30}",
                "\"usage\":{\"input_tokens\":\"100\",\"output_tokens\":30}",
            )).use { server ->
                assertThrows<JevSystemOneProviderException> { provider(server.baseUrl).evaluate(request()) }
            }
        } finally {
            providerLogger.detachAppender(appender)
            appender.stop()
        }

        val started = appender.list.first { it.message == "System One provider request started" }
        val startedFields = started.keyValuePairs.associate { it.key to it.value.toString() }
        assertEquals("jev", startedFields["providerId"])
        assertTrue(startedFields.containsKey("providerCallId"))
        assertTrue(startedFields.containsKey("evidenceCandidateId"))
        assertTrue(appender.list.any { it.message == "System One provider response received" })
        val rejected = appender.list.first { it.message == "System One provider response rejected" }
        val rejectedFields = rejected.keyValuePairs.associate { it.key to it.value.toString() }
        assertEquals("SYSTEM_ONE_RESPONSE_USAGE_INVALID", rejectedFields["failureReasonCode"])
        assertEquals("usage", rejectedFields["diagnosticField"])
        assertEquals("200", rejectedFields["httpStatus"])
        assertFalse(appender.list.any { event ->
            event.formattedMessage.contains("private claim text") ||
                event.formattedMessage.contains("private evidence text") ||
                event.keyValuePairs.any { field ->
                    val value = field.value.toString()
                    field.key == "providerReportedModelId" ||
                        value.contains("private claim text") ||
                        value.contains("private evidence text") ||
                        value.contains("jev-server-secret") ||
                        value.contains("response-only-model-marker")
                }
        })
    }

    @Test
    fun `rejects response bodies beyond the configured bound`() {
        TestServer(responseBody = " ".repeat(JevSystemOneSettings.MAX_RESPONSE_BYTES + 1)).use { server ->
            val failure = assertThrows<JevSystemOneProviderException> {
                provider(server.baseUrl).evaluate(request())
            }

            assertTrue(failure.message.orEmpty().contains("response exceeded"))
            assertEquals("SYSTEM_ONE_RESPONSE_TOO_LARGE", failure.failureReasonCode)
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun `reports request timeout without retrying or falling back`() {
        TestServer(responseBody = validResponse(), bodyDelayMillis = 500).use { server ->
            val failure = assertThrows<JevSystemOneProviderException> {
                provider(server.baseUrl, requestTimeoutMillis = 100).evaluate(request())
            }

            assertTrue(failure.message.orEmpty().contains("timed out"))
            assertEquals("SYSTEM_ONE_TIMEOUT", failure.failureReasonCode)
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun `does not call Jev for an empty request and rejects missing server credentials`() {
        TestServer(responseBody = validResponse()).use { server ->
            val provider = provider(server.baseUrl)
            val emptyResult = provider.evaluate(request().copy(evidencePassages = emptyList()))

            assertTrue(emptyResult.evidenceJudgements.isEmpty())
            assertEquals(0, server.requestCount)
        }

        val unconfigured = JevSystemOneProvider(JevSystemOneSettings.disabled(), objectMapper)
        val failure = assertThrows<JevSystemOneProviderException> { unconfigured.evaluate(request()) }
        assertEquals("SYSTEM_ONE_NOT_CONFIGURED", failure.failureReasonCode)
    }

    private fun provider(baseUrl: String, requestTimeoutMillis: Long = 5_000) = JevSystemOneProvider(
        settings(baseUrl, requestTimeoutMillis),
        objectMapper,
    )

    private fun settings(baseUrl: String, requestTimeoutMillis: Long = 5_000) = JevSystemOneSettings(
        apiKey = "jev-server-secret",
        baseUrl = baseUrl,
        requestTimeoutMillis = requestTimeoutMillis,
    )

    private fun request() = SemanticJudgementRequest(
        AtomicClaimForJudgement(UUID.randomUUID(), "private claim"),
        listOf(EvidencePassageForJudgement(UUID.randomUUID(), "private passage", null)),
    )

    private fun validResponse(): String {
        val directness = listOf(
            "No evidence addresses the claim.",
            "Only an indirect or weak connection is present.",
            "The passage is relevant but does not directly answer the claim.",
            "The passage directly addresses most of the claim.",
            "The passage directly reports evidence for the complete claim.",
        )
        val scope = listOf(
            "The population, conditions, or outcome do not match.",
            "A major material qualifier is missing or conflicts.",
            "The core claim matches, but at least one material qualifier is uncertain.",
            "The claim scope and nearly all material qualifiers match.",
            "The population, conditions, outcome, and material qualifiers match.",
        )
        val design = listOf(
            "No study design or method is described.",
            "The described design provides very weak evidence for this claim.",
            "The design provides limited or observational evidence.",
            "The design provides reasonably strong evidence for this claim.",
            "The design is rigorous and directly suited to assess this claim.",
        )
        val relevance = listOf(
            "The passage is unrelated to the claim.",
            "The passage has only a slight topical connection.",
            "The passage is relevant but only partly addresses the claim.",
            "The passage is strongly relevant to the claim.",
            "The passage is directly and fully relevant to the claim.",
        )
        fun score(levels: List<String>, score: Double, probabilities: Map<String, Double>) = mapOf(
            "type" to "score",
            "score" to score,
            "legend" to levels.mapIndexed { index, text -> index.toString() to text }.toMap(),
            "probabilities" to probabilities,
            "confidence" to 0.9,
        )
        return objectMapper.writeValueAsString(
            mapOf(
                "model" to "jev-1.13.0",
                "answers" to mapOf(
                    "judgement" to mapOf(
                        "type" to "choice",
                        "choice" to "DIRECT_SUPPORT",
                        "probabilities" to mapOf(
                            "DIRECT_SUPPORT" to 0.85,
                            "PARTIAL_SUPPORT" to 0.1,
                            "CONTRADICTS" to 0.02,
                            "UNRELATED" to 0.02,
                            "INSUFFICIENT" to 0.01,
                        ),
                        "confidence" to 0.85,
                    ),
                    "evidence_role" to mapOf(
                        "type" to "choice",
                        "choice" to "PRIMARY_FINDING",
                        "probabilities" to mapOf("PRIMARY_FINDING" to 0.6, "AUTHOR_SYNTHESIS" to 0.3, "SECONDARY_REPORT" to 0.1),
                        "confidence" to 0.6,
                    ),
                    "directness" to score(directness, 3.5, mapOf("3" to 0.5, "4" to 0.5, "0" to 0.0, "1" to 0.0, "2" to 0.0)),
                    "claim_scope_match" to score(scope, 2.0, mapOf("2" to 1.0, "0" to 0.0, "1" to 0.0, "3" to 0.0, "4" to 0.0)),
                    "study_design_quality" to score(design, 1.25, mapOf("1" to 0.75, "2" to 0.25, "0" to 0.0, "3" to 0.0, "4" to 0.0)),
                    "relevance" to score(relevance, 4.0, mapOf("4" to 1.0, "0" to 0.0, "1" to 0.0, "2" to 0.0, "3" to 0.0)),
                ),
                "usage" to mapOf("input_tokens" to 100, "output_tokens" to 30),
            ),
        )
    }

    private class TestServer(
        private val responseBody: String,
        private val status: Int = 200,
        private val bodyDelayMillis: Long = 0,
    ) : AutoCloseable {
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        private val executor = Executors.newSingleThreadExecutor()
        val baseUrl: String get() = "http://127.0.0.1:${server.address.port}"
        var requestCount: Int = 0
            private set
        var requestBody: String = ""
            private set
        var authorization: String? = null
            private set
        var method: String? = null
            private set
        var path: String? = null
            private set

        init {
            server.executor = executor
            server.createContext("/v1/systemone") { exchange ->
                requestCount += 1
                method = exchange.requestMethod
                path = exchange.requestURI.path
                authorization = exchange.requestHeaders.getFirst("Authorization")
                requestBody = exchange.requestBody.use { it.readAllBytes().toString(StandardCharsets.UTF_8) }
                if (bodyDelayMillis > 0) Thread.sleep(bodyDelayMillis)
                val body = responseBody.toByteArray(StandardCharsets.UTF_8)
                runCatching {
                    exchange.sendResponseHeaders(status, body.size.toLong())
                    exchange.responseBody.use { it.write(body) }
                }
            }
            server.start()
        }

        override fun close() {
            server.stop(0)
            executor.shutdownNow()
        }
    }
}
