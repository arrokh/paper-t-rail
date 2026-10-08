package com.papertrail.api.evidence.embedding

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.papertrail.api.analysis.configuration.ProviderSelection
import com.papertrail.api.analysis.configuration.RunConfigurationFactory
import com.papertrail.api.analysis.configuration.ValidationLimitsSnapshot
import com.papertrail.api.analysis.http.RunConfigurationRequest
import com.papertrail.api.infrastructure.providers.DataCategory
import com.papertrail.api.infrastructure.providers.EMBEDDING_ROLE
import com.papertrail.api.infrastructure.providers.ProviderCallGate
import com.papertrail.api.infrastructure.providers.ProviderCallRejectedException
import com.papertrail.api.infrastructure.providers.ProviderCatalog
import com.papertrail.api.infrastructure.providers.externalProviderConsent
import com.papertrail.api.evidence.domain.EmbeddingProfile
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import com.papertrail.api.external.ollama.OllamaEmbeddingException
import com.papertrail.api.external.ollama.OllamaEmbeddingProvider
import com.papertrail.api.external.ollama.OllamaEmbeddingSettings

class OllamaEmbeddingProviderContractTest {
    @Test
    fun `sends the configured model and only authorized input to the Ollama embed endpoint`() {
        OllamaTestServer(responseFor("embeddinggemma-2:270m", listOf(0.25, -0.5, 0.75))).use { server ->
            val settings = settings(server.baseUrl, apiKey = "server-only-secret")
            val factory = configurationFactory(settings)
            val configuration = factory.from(
                RunConfigurationRequest(embeddingProvider = OllamaEmbeddingSettings.EMBEDDINGGEMMA_PROVIDER_ID),
            )
            val provider = OllamaEmbeddingProvider(settings, ProviderCallGate(factoryCatalog(settings)))

            val chunkVector = provider.embed(
                "a private cited-paper chunk",
                EmbeddingRequestContext(configuration, DataCategory.CITED_PAPER_CHUNKS),
            )
            val queryVector = provider.embed(
                "a private claim query",
                EmbeddingRequestContext(configuration, DataCategory.ATOMIC_CLAIMS),
            )

            assertEquals(listOf(0.25f, -0.5f, 0.75f), chunkVector.toList())
            assertEquals(chunkVector.toList(), queryVector.toList())
            assertEquals(2, server.requestCount)
            assertEquals("POST", server.method)
            assertEquals("/api/embed", server.path)
            assertEquals("Bearer server-only-secret", server.authorization)
            assertEquals(listOf("a private cited-paper chunk", "a private claim query"), server.requestBodies.map { body ->
                val request = mapper.readTree(body)
                assertEquals("embeddinggemma-2:270m", request.path("model").asText())
                assertTrue(request.path("truncate").isBoolean)
                assertFalse(request.path("truncate").asBoolean())
                request.path("input").single().asText()
            })
            assertEquals("LOCAL", configuration.embedding.trustBoundary)
            assertTrue(configuration.externalProviderConsents.isEmpty())

            val directoryJson = mapper.writeValueAsString(factoryCatalog(settings).directory())
            val snapshotJson = factory.toJson(configuration)
            assertFalse(directoryJson.contains(server.baseUrl))
            assertFalse(directoryJson.contains("server-only-secret"))
            assertFalse(snapshotJson.contains(server.baseUrl))
            assertFalse(snapshotJson.contains("server-only-secret"))
            assertFalse(settings.toString().contains(server.baseUrl))
            assertFalse(settings.toString().contains("server-only-secret"))
        }
    }

    @Test
    fun `does not offer Ollama until its endpoint and model configuration are valid`() {
        val invalidModel = settings("http://127.0.0.1:11434").copy(modelId = " ")
        val missingEndpoint = settings("")
        val endpointCredentials = settings("http://user:password@127.0.0.1:11434")
        val externalEndpoint = settings(
            "http://127.0.0.1:11434",
            trustedHosts = setOf("ollama.internal"),
        )
        val valid = settings("http://127.0.0.1:11434")

        assertFalse(factoryCatalog(invalidModel).directory().providers.getValue(EMBEDDING_ROLE).any { it.providerId == OllamaEmbeddingSettings.EMBEDDINGGEMMA_PROVIDER_ID })
        assertFalse(factoryCatalog(missingEndpoint).directory().providers.getValue(EMBEDDING_ROLE).any { it.providerId == OllamaEmbeddingSettings.EMBEDDINGGEMMA_PROVIDER_ID })
        assertFalse(factoryCatalog(endpointCredentials).directory().providers.getValue(EMBEDDING_ROLE).any { it.providerId == OllamaEmbeddingSettings.EMBEDDINGGEMMA_PROVIDER_ID })
        assertTrue(factoryCatalog(externalEndpoint).directory().providers.getValue(EMBEDDING_ROLE).any { it.providerId == OllamaEmbeddingSettings.EMBEDDINGGEMMA_PROVIDER_ID })
        assertTrue(factoryCatalog(valid).directory().providers.getValue(EMBEDDING_ROLE).any { it.providerId == OllamaEmbeddingSettings.EMBEDDINGGEMMA_PROVIDER_ID })
    }

    @Test
    fun `processes a previously pinned Nomic run without offering Nomic for new runs`() {
        val embedding = List(768) { 0.25 }
        OllamaTestServer(responseFor(OllamaEmbeddingSettings.LEGACY_NOMIC_MODEL_ID, embedding)).use { server ->
            val settings = settings(server.baseUrl).copy(dimension = 768)
            val catalog = factoryCatalog(settings)
            val newRunConfiguration = configurationFactory(settings).from(
                RunConfigurationRequest(embeddingProvider = OllamaEmbeddingSettings.EMBEDDINGGEMMA_PROVIDER_ID),
            )
            val legacyRegistration = catalog.requireAvailableForPinnedRun(EMBEDDING_ROLE, OllamaEmbeddingSettings.LEGACY_NOMIC_PROVIDER_ID)
            val legacySelection = ProviderSelection(
                provider = legacyRegistration.providerId,
                version = legacyRegistration.version,
                model = legacyRegistration.model,
                trustBoundary = legacyRegistration.trustBoundary.id,
                dataCategories = legacyRegistration.dataCategories.map(DataCategory::id).sorted(),
                configurationFingerprint = legacyRegistration.configurationFingerprint,
                embeddingDimension = legacyRegistration.embeddingDimension,
            )
            val legacyProfile = EmbeddingProfile.from(legacySelection)
            assertEquals("ollama", legacySelection.provider)
            assertEquals(OllamaEmbeddingSettings.LEGACY_NOMIC_MODEL_ID, legacySelection.model)
            assertTrue(legacyProfile.profileHash != newRunConfiguration.retrieval.embeddingProfileHash)
            val legacyConfiguration = newRunConfiguration.copy(
                embedding = legacySelection,
                retrieval = newRunConfiguration.retrieval.copy(embeddingProfileHash = legacyProfile.profileHash),
            )
            val provider = OllamaEmbeddingProvider(
                settings.forLegacyNomicCompatibility(),
                ProviderCallGate(catalog),
            )

            val vector = provider.embed(
                "historical cited-paper chunk",
                EmbeddingRequestContext(legacyConfiguration, DataCategory.CITED_PAPER_CHUNKS),
            )

            assertEquals(768, vector.size)
            assertEquals(setOf("local", OllamaEmbeddingSettings.EMBEDDINGGEMMA_PROVIDER_ID),
                catalog.directory().providers.getValue(EMBEDDING_ROLE).map { it.providerId }.toSet())
            assertEquals(OllamaEmbeddingSettings.LEGACY_NOMIC_MODEL_ID, mapper.readTree(server.requestBodies.single()).path("model").asText())
        }
    }

    @Test
    fun `classifies endpoints outside the trusted host list as external without a terms-review gate and requires fresh consent`() {
        OllamaTestServer(responseFor("embeddinggemma-2:270m", listOf(0.25, -0.5, 0.75))).use { server ->
            val settings = settings(
                baseUrl = server.baseUrl,
                trustedHosts = setOf("ollama.internal"),
            )
            val factory = configurationFactory(settings)
            val catalog = factoryCatalog(settings)
            val provider = OllamaEmbeddingProvider(
                settings,
                ProviderCallGate(catalog),
            )
            val requiredCategories = listOf("atomic_claims", "cited_paper_chunks", "embedding_input")

            val externalOption = catalog.directory().providers.getValue(EMBEDDING_ROLE)
                .single { it.providerId == OllamaEmbeddingSettings.EMBEDDINGGEMMA_PROVIDER_ID }
            assertEquals("EXTERNAL", externalOption.trustBoundary)
            assertTrue(externalOption.retentionDisclosure!!.contains("Retention and deletion details are unknown"))
            assertThrows<IllegalArgumentException> {
                factory.from(RunConfigurationRequest(embeddingProvider = OllamaEmbeddingSettings.EMBEDDINGGEMMA_PROVIDER_ID))
            }
            val consented = factory.from(
                RunConfigurationRequest(
                    embeddingProvider = OllamaEmbeddingSettings.EMBEDDINGGEMMA_PROVIDER_ID,
                    externalProviderConsents = listOf(
                        externalProviderConsent(catalog, OllamaEmbeddingSettings.EMBEDDINGGEMMA_PROVIDER_ID, requiredCategories),
                    ),
                ),
            )

            assertThrows<ProviderCallRejectedException> {
                provider.embed(
                    "private cited-paper chunk",
                    EmbeddingRequestContext(consented.copy(externalProviderConsents = emptyList()), DataCategory.CITED_PAPER_CHUNKS),
                )
            }
            assertThrows<ProviderCallRejectedException> {
                provider.embed(
                    "private cited-paper chunk",
                    EmbeddingRequestContext(
                        consented.copy(embedding = consented.embedding.copy(embeddingDimension = null)),
                        DataCategory.CITED_PAPER_CHUNKS,
                    ),
                )
            }
            assertEquals(0, server.requestCount)

            provider.embed("private cited-paper chunk", EmbeddingRequestContext(consented, DataCategory.CITED_PAPER_CHUNKS))
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun `reports an unavailable Ollama endpoint without falling back`() {
        val port = ServerSocket(0).use { it.localPort }
        val settings = settings("http://127.0.0.1:$port", requestTimeoutMillis = 1_000)
        val provider = provider(settings)
        val configuration = configurationFactory(settings).from(
            RunConfigurationRequest(embeddingProvider = OllamaEmbeddingSettings.EMBEDDINGGEMMA_PROVIDER_ID),
        )

        val failure = assertThrows<OllamaEmbeddingException> {
            provider.embed("claim", EmbeddingRequestContext(configuration, DataCategory.ATOMIC_CLAIMS))
        }

        assertTrue(failure.message.orEmpty().contains("endpoint is unavailable"))
    }

    @Test
    fun `applies the configured timeout while reading an Ollama response body`() {
        OllamaTestServer(
            responseFor("embeddinggemma-2:270m", listOf(0.25, -0.5, 0.75)),
            bodyDelayMillis = 600,
        ).use { server ->
            val settings = settings(server.baseUrl, requestTimeoutMillis = 150)
            val configuration = configurationFactory(settings).from(
                RunConfigurationRequest(embeddingProvider = OllamaEmbeddingSettings.EMBEDDINGGEMMA_PROVIDER_ID),
            )

            val failure = assertThrows<OllamaEmbeddingException> {
                provider(settings).embed("claim", EmbeddingRequestContext(configuration, DataCategory.ATOMIC_CLAIMS))
            }

            assertTrue(failure.message.orEmpty().contains("request timed out"))
        }
    }

    @Test
    fun `rejects malformed Ollama response bodies`() {
        OllamaTestServer("not-json").use { server ->
            val settings = settings(server.baseUrl)
            val configuration = configurationFactory(settings).from(
                RunConfigurationRequest(embeddingProvider = OllamaEmbeddingSettings.EMBEDDINGGEMMA_PROVIDER_ID),
            )

            val failure = assertThrows<OllamaEmbeddingException> {
                provider(settings).embed("claim", EmbeddingRequestContext(configuration, DataCategory.ATOMIC_CLAIMS))
            }

            assertTrue(failure.message.orEmpty().contains("malformed embedding response"))
        }
    }

    @Test
    fun `rejects an oversized Ollama response body`() {
        OllamaTestServer("x".repeat(OllamaEmbeddingSettings.MAX_RESPONSE_BYTES + 1)).use { server ->
            val settings = settings(server.baseUrl)
            val configuration = configurationFactory(settings).from(
                RunConfigurationRequest(embeddingProvider = OllamaEmbeddingSettings.EMBEDDINGGEMMA_PROVIDER_ID),
            )

            val failure = assertThrows<OllamaEmbeddingException> {
                provider(settings).embed("claim", EmbeddingRequestContext(configuration, DataCategory.ATOMIC_CLAIMS))
            }

            assertTrue(failure.message.orEmpty().contains("response exceeded the configured response limit"))
        }
    }

    @Test
    fun `rejects vectors whose dimensions differ from the configured pgvector profile`() {
        OllamaTestServer(responseFor("embeddinggemma-2:270m", listOf(0.25, -0.5))).use { server ->
            val settings = settings(server.baseUrl)
            val configuration = configurationFactory(settings).from(
                RunConfigurationRequest(embeddingProvider = OllamaEmbeddingSettings.EMBEDDINGGEMMA_PROVIDER_ID),
            )

            val failure = assertThrows<OllamaEmbeddingException> {
                provider(settings).embed("claim", EmbeddingRequestContext(configuration, DataCategory.ATOMIC_CLAIMS))
            }

            assertTrue(failure.message.orEmpty().contains("dimension that does not match"))
        }
    }

    @Test
    fun `pins model endpoint fingerprint and vector dimension in the Analysis Run embedding profile`() {
        val settings = settings("http://127.0.0.1:11434")
        val configuration = configurationFactory(settings).from(
            RunConfigurationRequest(embeddingProvider = OllamaEmbeddingSettings.EMBEDDINGGEMMA_PROVIDER_ID),
        )
        val differentEndpointConfiguration = configurationFactory(settings.copy(baseUrl = "http://127.0.0.1:11435"))
            .from(RunConfigurationRequest(embeddingProvider = OllamaEmbeddingSettings.EMBEDDINGGEMMA_PROVIDER_ID))
        val differentDimensionConfiguration = configurationFactory(settings.copy(dimension = 4))
            .from(RunConfigurationRequest(embeddingProvider = OllamaEmbeddingSettings.EMBEDDINGGEMMA_PROVIDER_ID))

        assertEquals("embeddinggemma-2:270m", configuration.embedding.model)
        assertEquals(3, configuration.embedding.embeddingDimension)
        assertTrue(configuration.retrieval.embeddingProfileHash.matches(Regex("[0-9a-f]{64}")))
        assertTrue(configuration.retrieval.embeddingProfileHash != differentEndpointConfiguration.retrieval.embeddingProfileHash)
        assertTrue(configuration.retrieval.embeddingProfileHash != differentDimensionConfiguration.retrieval.embeddingProfileHash)
    }

    private fun provider(settings: OllamaEmbeddingSettings): OllamaEmbeddingProvider = OllamaEmbeddingProvider(
        settings,
        ProviderCallGate(factoryCatalog(settings)),
    )

    private fun configurationFactory(settings: OllamaEmbeddingSettings): RunConfigurationFactory = RunConfigurationFactory(
        providerCatalog = factoryCatalog(settings),
        parserId = "grobid",
        parserVersion = "0.9.1-crf",
        languageDetectorVersion = "0.6",
        limits = ValidationLimitsSnapshot(52_428_800, 500, 5_000_000, 100_000, 100, 0.65),
    )

    private fun factoryCatalog(settings: OllamaEmbeddingSettings): ProviderCatalog = ProviderCatalog.safeDefaults(
        ollamaEmbeddingSettings = settings,
    )

    private fun settings(
        baseUrl: String,
        apiKey: String? = null,
        trustedHosts: Set<String> = setOf("localhost", "127.0.0.1"),
        requestTimeoutMillis: Long = 5_000,
        retentionDisclosure: String? = null,
    ): OllamaEmbeddingSettings = OllamaEmbeddingSettings(
        enabled = true,
        baseUrl = baseUrl,
        modelId = OllamaEmbeddingSettings.EMBEDDINGGEMMA_MODEL_ID,
        dimension = 3,
        apiKey = apiKey,
        providerId = OllamaEmbeddingSettings.EMBEDDINGGEMMA_PROVIDER_ID,
        trustedHosts = trustedHosts,
        requestTimeoutMillis = requestTimeoutMillis,
        retentionDisclosure = retentionDisclosure,
    )

    private class OllamaTestServer(
        private val responseBody: String,
        private val status: Int = 200,
        private val bodyDelayMillis: Long = 0,
    ) : AutoCloseable {
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val baseUrl: String get() = "http://127.0.0.1:${server.address.port}"
        var requestCount: Int = 0
            private set
        var method: String? = null
            private set
        var path: String? = null
            private set
        var authorization: String? = null
            private set
        val requestBodies: MutableList<String> = mutableListOf()

        init {
            server.createContext("/api/embed") { exchange ->
                requestCount++
                method = exchange.requestMethod
                path = exchange.requestURI.path
                authorization = exchange.requestHeaders.getFirst("Authorization")
                val requestBody = exchange.requestBody.readAllBytes().toString(StandardCharsets.UTF_8)
                requestBodies += requestBody
                val bytes = responseBody.toByteArray(StandardCharsets.UTF_8)
                exchange.sendResponseHeaders(status, bytes.size.toLong())
                exchange.responseBody.use { response ->
                    if (bodyDelayMillis > 0 && bytes.isNotEmpty()) {
                        response.write(bytes, 0, 1)
                        response.flush()
                        Thread.sleep(bodyDelayMillis)
                        response.write(bytes, 1, bytes.size - 1)
                    } else {
                        response.write(bytes)
                    }
                }
            }
            server.start()
        }

        override fun close() {
            server.stop(0)
        }
    }

    companion object {
        private val mapper: ObjectMapper = jacksonObjectMapper()

        private fun responseFor(model: String, vector: List<Double>): String =
            """{"model":"$model","embeddings":[[${vector.joinToString(",")}]]}"""
    }
}
