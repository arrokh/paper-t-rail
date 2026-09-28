package com.papertrail.api.evidence.verification.provider

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.papertrail.api.analysis.configuration.RunConfigurationFactory
import com.papertrail.api.analysis.configuration.ValidationLimitsSnapshot
import com.papertrail.api.analysis.http.RunConfigurationRequest
import com.papertrail.api.evidence.verification.domain.AtomicClaimForJudgement
import com.papertrail.api.evidence.verification.domain.EvidenceJudgementKind
import com.papertrail.api.evidence.verification.domain.EvidencePassageForJudgement
import com.papertrail.api.evidence.verification.domain.EvidenceRole
import com.papertrail.api.evidence.verification.domain.SemanticJudgementRequest
import com.papertrail.api.infrastructure.providers.DataCategory
import com.papertrail.api.infrastructure.providers.ProviderCallGate
import com.papertrail.api.infrastructure.providers.ProviderCallPayload
import com.papertrail.api.infrastructure.providers.ProviderCallRejectedException
import com.papertrail.api.infrastructure.providers.ProviderCatalog
import com.papertrail.api.infrastructure.providers.SYSTEM_ONE_ROLE
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.util.UUID

class LayaSystemOneProviderContractTest {
    @Test
    fun `maps recorded System One responses to all Evidence Judgement kinds and score fields`() {
        val kinds = listOf(
            EvidenceJudgementKind.DIRECT_SUPPORT,
            EvidenceJudgementKind.PARTIAL_SUPPORT,
            EvidenceJudgementKind.CONTRADICTS,
            EvidenceJudgementKind.UNRELATED,
            EvidenceJudgementKind.INSUFFICIENT,
        )
        val roles = listOf(
            EvidenceRole.PRIMARY_FINDING,
            EvidenceRole.AUTHOR_SYNTHESIS,
            EvidenceRole.SECONDARY_REPORT,
            EvidenceRole.PRIMARY_FINDING,
            EvidenceRole.AUTHOR_SYNTHESIS,
        )
        val fixture = mapper.readTree(javaClass.getResourceAsStream("/provider-fixtures/laya-systemone-responses.json"))
        LayaTestServer(response = { requestIndex, _ -> mapper.writeValueAsString(fixture.get(kinds[requestIndex].name)) }).use { server ->
            val provider = provider(settings(server.baseUrl))
            val passages = kinds.mapIndexed { index, _ ->
                EvidencePassageForJudgement(UUID.randomUUID(), "Evidence passage $index", "Results")
            }

            val result = provider.evaluate(request(passages))

            assertEquals(kinds, result.evidenceJudgements.map { it.judgement })
            assertEquals(roles, result.evidenceJudgements.map { it.evidenceRole })
            assertTrue(result.evidenceJudgements.all { it.confidence == 0.9 })
            assertEquals(passages.map { it.id }, result.evidenceJudgements.map { it.evidenceCandidateId })
            result.evidenceJudgements.forEach { judgement ->
                assertEquals(0.75, judgement.directness)
                assertEquals(0.5, judgement.claimScopeMatch)
                assertEquals(1.0, judgement.studyDesignQuality)
                assertEquals(0.25, judgement.relevance)
                assertTrue(judgement.confidence in 0.0..1.0)
            }
            assertEquals(passages.size, server.requests.size)
            server.requests.forEachIndexed { index, body ->
                val state = body.path("state")
                assertEquals(setOf("claim", "evidence", "section"), state.fieldNames().asSequence().toSet())
                assertEquals("A claim with its material qualifier", state.path("claim").asText())
                assertEquals(passages[index].text, state.path("evidence").asText())
                assertEquals("Results", state.path("section").asText())
                assertEquals("typed-decisions", body.path("model").asText())
                assertEquals(
                    setOf("judgement", "evidence_role", "directness", "claim_scope_match", "study_design_quality", "relevance"),
                    body.path("questions").fieldNames().asSequence().toSet(),
                )
                assertFalse(mapper.writeValueAsString(body).contains("Source Document"))
            }
        }
    }

    @Test
    fun `pins checkpoint runtime mapping and endpoint fingerprint without persisting credentials`() {
        LayaTestServer(response = { _, _ -> fixtureResponse() }).use { server ->
            val settings = settings(server.baseUrl, apiKey = "private-sidecar-key")
            val factory = configurationFactory(settings)
            val snapshot = factory.from(RunConfigurationRequest(systemOneProvider = LayaSystemOneProvider.PROVIDER_ID))
            val directoryJson = mapper.writeValueAsString(catalog(settings).directory())
            val snapshotJson = factory.toJson(snapshot)

            assertEquals(LayaSystemOneProvider.PROVIDER_VERSION, snapshot.systemOne.version)
            assertTrue(snapshot.systemOne.version.length <= 80)
            assertEquals(LayaSystemOneProvider.PINNED_MODEL_ID, snapshot.systemOne.model)
            assertTrue(snapshot.systemOne.configurationFingerprint!!.matches(Regex("[0-9a-f]{64}")))
            assertEquals("LOCAL", snapshot.systemOne.trustBoundary)
            assertEquals(listOf("atomic_claims", "evidence_passages"), snapshot.systemOne.dataCategories)
            assertTrue(directoryJson.contains(LayaSystemOneProvider.PINNED_MODEL_ID))
            assertFalse(directoryJson.contains(server.baseUrl))
            assertFalse(directoryJson.contains("private-sidecar-key"))
            assertFalse(snapshotJson.contains(server.baseUrl))
            assertFalse(snapshotJson.contains("private-sidecar-key"))
            assertFalse(settings.toString().contains("private-sidecar-key"))
        }
    }

    @Test
    fun `does not expose Laya by default and keeps the default Analysis Run selection on mock`() {
        val factory = configurationFactory(LayaSystemOneSettings.disabled())

        val snapshot = factory.from(factory.parseRequest(null))

        assertEquals("mock", snapshot.systemOne.provider)
        assertFalse(catalog(LayaSystemOneSettings.disabled()).directory().providers.getValue(SYSTEM_ONE_ROLE)
            .any { it.providerId == LayaSystemOneProvider.PROVIDER_ID })
    }

    @Test
    fun `a configured Laya provider cannot receive payloads for a run pinned to mock`() {
        val settings = settings("http://127.0.0.1:8000")
        val factory = configurationFactory(settings)
        val snapshot = factory.from(RunConfigurationRequest(systemOneProvider = "mock"))
        var outboundCallStarted = false

        assertEquals("mock", snapshot.systemOne.provider)
        assertTrue(catalog(settings).directory().providers.getValue(SYSTEM_ONE_ROLE)
            .any { it.providerId == LayaSystemOneProvider.PROVIDER_ID })
        assertThrows(ProviderCallRejectedException::class.java) {
            ProviderCallGate(catalog(settings)).call(
                SYSTEM_ONE_ROLE,
                LayaSystemOneProvider.PROVIDER_ID,
                ProviderCallPayload(mapOf(
                    DataCategory.ATOMIC_CLAIMS to mapper.readTree("\"claim\""),
                    DataCategory.EVIDENCE_PASSAGES to mapper.readTree("\"passage\""),
                )),
                snapshot,
            ) { outboundCallStarted = true }
        }
        assertFalse(outboundCallStarted)
    }

    @Test
    fun `requires an authenticated endpoint on a trusted local host before offering Laya`() {
        val noKey = settings("http://127.0.0.1:8000", apiKey = null)
        val credentialedUrl = settings("http://user:pass@127.0.0.1:8000")
        val queryUrl = settings("http://127.0.0.1:8000?token=secret")
        val untrustedHost = settings("http://remote.example:8000")
        val explicitlyDisabled = settings("http://127.0.0.1:8000").copy(enabled = false)
        val valid = settings("http://127.0.0.1:8000")

        listOf(noKey, credentialedUrl, queryUrl, untrustedHost, explicitlyDisabled).forEach { invalid ->
            assertFalse(catalog(invalid).directory().providers
                .getValue(SYSTEM_ONE_ROLE).any { it.providerId == LayaSystemOneProvider.PROVIDER_ID })
        }
        assertTrue(catalog(valid).directory().providers
            .getValue(SYSTEM_ONE_ROLE).any { it.providerId == LayaSystemOneProvider.PROVIDER_ID })
    }

    @Test
    fun `rejects a sidecar endpoint change against the immutable Analysis Run snapshot`() {
        val runSettings = settings("http://laya:8000")
        val changedSettings = runSettings.copy(baseUrl = "http://laya:8001")
        val runFactory = configurationFactory(runSettings)
        val runSnapshot = runFactory.from(RunConfigurationRequest(systemOneProvider = LayaSystemOneProvider.PROVIDER_ID))
        val changedCatalog = catalog(changedSettings)
        var outboundCallStarted = false

        assertThrows(ProviderCallRejectedException::class.java) {
            ProviderCallGate(changedCatalog).call(
                SYSTEM_ONE_ROLE,
                LayaSystemOneProvider.PROVIDER_ID,
                ProviderCallPayload(mapOf(
                    DataCategory.ATOMIC_CLAIMS to mapper.readTree("\"claim\""),
                    DataCategory.EVIDENCE_PASSAGES to mapper.readTree("\"passage\""),
                )),
                runSnapshot,
            ) { outboundCallStarted = true }
        }
        assertFalse(outboundCallStarted)
    }

    @Test
    fun `rejects malformed unsupported and incomplete Laya responses`() {
        val malformedResponses = listOf(
            "not-json",
            "{}",
            mapper.writeValueAsString(fixtureNode().deepCopy<JsonNode>().also { (it as ObjectNode).remove("routing") }),
            mapper.writeValueAsString(fixtureNode().deepCopy<JsonNode>().also {
                (it.path("answers") as ObjectNode).put("judgement", "unknown")
            }),
            mapper.writeValueAsString(fixtureNode().deepCopy<JsonNode>().also {
                (it.path("answers") as ObjectNode).remove("relevance")
            }),
            fixtureResponse { (it.path("answers").path("judgement").path("probabilities") as ObjectNode).remove("UNRELATED") },
            fixtureResponse { (it.path("answers").path("judgement") as ObjectNode).remove("answer_confidence") },
            fixtureResponse { (it.path("answers").path("judgement").path("probabilities") as ObjectNode).put("DIRECT_SUPPORT", 0.95) },
            fixtureResponse { (it.path("answers").path("judgement") as ObjectNode).put("confidence", 1.1) },
            fixtureResponse { (it.path("answers").path("directness").path("legend") as ObjectNode).put("0", "unexpected") },
            fixtureResponse { (it.path("answers").path("directness") as ObjectNode).put("score", 5.0) },
            fixtureResponse { (it.path("usage") as ObjectNode).put("input_tokens", -1) },
            fixtureResponse { (it.path("usage") as ObjectNode).replace("input_tokens", mapper.readTree("18446744073709551616")) },
            mapper.writeValueAsString(fixtureNode().deepCopy<JsonNode>().also {
                val answers = it.path("answers") as ObjectNode
                (answers.path("judgement") as ObjectNode).put("choice", "NOT_A_JUDGEMENT")
            }),
        )

        malformedResponses.forEach { responseBody ->
            LayaTestServer(response = { _, _ -> responseBody }).use { server ->
                val failure = assertThrows(LayaSystemOneProviderException::class.java) {
                    provider(settings(server.baseUrl)).evaluate(request(listOf(passage("candidate passage"))))
                }
                assertFalse(failure.message.orEmpty().contains("candidate passage"))
                assertFalse(failure.message.orEmpty().contains(server.baseUrl))
            }
        }
    }

    @Test
    fun `classifies an over-limit HTTP 422 without exposing its response body`() {
        LayaTestServer(
            { _, _ -> """{"detail":"Complete state and questions exceed the 1024-token context limit."}""" },
            status = 422,
        ).use { server ->
            val failure = assertThrows(LayaSystemOneProviderException::class.java) {
                provider(settings(server.baseUrl)).evaluate(request(listOf(passage("candidate passage"))))
            }

            assertEquals(LayaSystemOneProviderException.CONTEXT_LIMIT_EXCEEDED, failure.failureReasonCode)
            assertFalse(failure.message.orEmpty().contains("candidate passage"))
            assertFalse(failure.message.orEmpty().contains("Complete state and questions"))
            assertEquals(1, server.requests.size)
        }
    }

    @Test
    fun `reports HTTP failures timeout and unavailable runtime without fabricated results`() {
        LayaTestServer({ _, _ -> fixtureResponse() }, status = 503).use { server ->
            val failure = assertThrows(LayaSystemOneProviderException::class.java) {
                provider(settings(server.baseUrl)).evaluate(request(listOf(passage("candidate passage"))))
            }
            assertTrue(failure.message.orEmpty().contains("HTTP"))
        }

        LayaTestServer({ _, _ -> fixtureResponse() }, bodyDelayMillis = 600).use { server ->
            val failure = assertThrows(LayaSystemOneProviderException::class.java) {
                provider(settings(server.baseUrl, requestTimeoutMillis = 100)).evaluate(request(listOf(passage("candidate passage"))))
            }
            assertTrue(failure.message.orEmpty().contains("timed out"))
        }

        val unavailablePort = ServerSocket(0).use { it.localPort }
        val unavailable = assertThrows(LayaSystemOneProviderException::class.java) {
            provider(settings("http://127.0.0.1:$unavailablePort")).evaluate(request(listOf(passage("candidate passage"))))
        }
        assertTrue(unavailable.message.orEmpty().contains("unavailable"))
    }

    @Test
    fun `sends the API key only to the private sidecar and does not fall back after provider failure`() {
        LayaTestServer({ _, _ -> fixtureResponse() }, status = 500).use { server ->
            val settings = settings(server.baseUrl, apiKey = "private-sidecar-key")
            val failure = assertThrows(LayaSystemOneProviderException::class.java) {
                provider(settings).evaluate(request(listOf(passage("only this passage"))))
            }

            assertTrue(failure.message.orEmpty().contains("HTTP"))
            assertEquals("Bearer private-sidecar-key", server.authorization)
            assertEquals(1, server.requests.size)
        }
    }

    private fun fixtureNode(): JsonNode = mapper.readTree(
        javaClass.getResourceAsStream("/provider-fixtures/laya-systemone-responses.json"),
    ).path("DIRECT_SUPPORT")

    private fun fixtureResponse(): String = mapper.writeValueAsString(fixtureNode())

    private fun fixtureResponse(change: (ObjectNode) -> Unit): String =
        fixtureNode().deepCopy<ObjectNode>().apply(change).let { mapper.writeValueAsString(it) }

    private fun provider(settings: LayaSystemOneSettings): LayaSystemOneProvider = LayaSystemOneProvider(
        settings = settings,
        objectMapper = mapper,
    )

    private fun catalog(settings: LayaSystemOneSettings): ProviderCatalog =
        ProviderCatalog.safeDefaults(layaSystemOneSettings = settings)

    private fun configurationFactory(settings: LayaSystemOneSettings): RunConfigurationFactory = RunConfigurationFactory(
        objectMapper = mapper,
        providerCatalog = catalog(settings),
        parserId = "grobid",
        parserVersion = "0.9.1-crf",
        languageDetectorVersion = "0.6",
        limits = ValidationLimitsSnapshot(52_428_800, 500, 5_000_000, 100_000, 100, 0.65),
    )

    private fun settings(
        baseUrl: String,
        apiKey: String? = "test-sidecar-key",
        requestTimeoutMillis: Long = 5_000,
    ): LayaSystemOneSettings = LayaSystemOneSettings(
        enabled = true,
        baseUrl = baseUrl,
        apiKey = apiKey,
        trustedHosts = setOf("127.0.0.1", "localhost", "laya"),
        requestTimeoutMillis = requestTimeoutMillis,
    )

    private fun request(passages: List<EvidencePassageForJudgement>) = SemanticJudgementRequest(
        atomicClaim = AtomicClaimForJudgement(UUID.randomUUID(), "A claim with its material qualifier"),
        evidencePassages = passages,
    )

    private fun passage(text: String) = EvidencePassageForJudgement(UUID.randomUUID(), text, "Results")

    private class LayaTestServer(
        private val response: (Int, JsonNode) -> String,
        private val status: Int = 200,
        private val bodyDelayMillis: Long = 0,
    ) : AutoCloseable {
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val baseUrl: String get() = "http://127.0.0.1:${server.address.port}"
        val requests = mutableListOf<JsonNode>()
        var authorization: String? = null
            private set

        init {
            server.createContext("/v1/systemone") { exchange ->
                authorization = exchange.requestHeaders.getFirst("Authorization")
                val body = mapper.readTree(exchange.requestBody.readAllBytes().toString(StandardCharsets.UTF_8))
                requests.add(body)
                val bytes = response(requests.lastIndex, body).toByteArray(StandardCharsets.UTF_8)
                if (bodyDelayMillis > 0) Thread.sleep(bodyDelayMillis)
                runCatching {
                    exchange.sendResponseHeaders(status, bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                }
            }
            server.start()
        }

        override fun close() = server.stop(0)
    }

    companion object {
        private val mapper = jacksonObjectMapper()
    }
}
