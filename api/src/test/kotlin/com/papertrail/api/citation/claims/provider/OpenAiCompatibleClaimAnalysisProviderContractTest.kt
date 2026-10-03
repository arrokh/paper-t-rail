package com.papertrail.api.citation.claims.provider

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.papertrail.api.analysis.configuration.ExternalProviderConsentSnapshot
import com.papertrail.api.analysis.configuration.RunConfigurationFactory
import com.papertrail.api.analysis.configuration.ValidationLimitsSnapshot
import com.papertrail.api.analysis.http.RunConfigurationRequest
import com.papertrail.api.citation.claims.domain.ClaimAnalysisContextInput
import com.papertrail.api.citation.claims.domain.ClaimAnalysisRequest
import com.papertrail.api.citation.claims.service.ClaimAnalysisRequestFactory
import com.papertrail.api.citation.parsing.ParsedBibliographyEntry
import com.papertrail.api.citation.parsing.ParsedCitationContext
import com.papertrail.api.citation.parsing.ParsedCitationOccurrence
import com.papertrail.api.citation.parsing.ParsedScientificDocument
import com.papertrail.api.citation.parsing.ParsedSection
import com.papertrail.api.infrastructure.providers.CLAIM_EXTRACTOR_ROLE
import com.papertrail.api.infrastructure.providers.DataCategory
import com.papertrail.api.infrastructure.providers.ProviderCallGate
import com.papertrail.api.infrastructure.providers.ProviderCallRejectedException
import com.papertrail.api.infrastructure.providers.ProviderCatalog
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

class OpenAiCompatibleClaimAnalysisProviderContractTest {
    private val objectMapper: ObjectMapper = jacksonObjectMapper()
        .registerKotlinModule()
        .registerModule(JavaTimeModule())
    private val authorizationHeaders = CopyOnWriteArrayList<String>()

    @Test
    fun `sends only classified contexts and minimal GROBID metadata then maps selected target sets`() = withServer { server, requests ->
        val settings = localSettings(server).copy(apiKey = "server-side-secret")
        val providerCatalog = ProviderCatalog.safeDefaults(openAiCompatibleClaimAnalysisSettings = settings)
        val provider = provider(settings, providerCatalog)
        val request = requestFactory().from(documentWithOneContextAndTwoTargets())
        val configuration = configuration(providerCatalog)

        val result = provider.analyze(request, configuration)

        assertEquals(1, result.size)
        assertEquals(1, result.single().claims.size)
        assertEquals(listOf(request.contexts.single().targetCandidates.first().key), result.single().claims.single().citationTargetKeys)
        assertEquals(1, requests.size)
        assertEquals(listOf("Bearer server-side-secret"), authorizationHeaders)
        assertFalse(requests.single().contains("server-side-secret"))
        val httpRequest = objectMapper.readTree(requests.single())
        assertEquals("fixture-model", httpRequest.path("model").asText())
        assertEquals("json_object", httpRequest.path("response_format").path("type").asText())
        assertEquals(false, httpRequest.path("stream").asBoolean())
        val user = objectMapper.readTree(httpRequest.path("messages")[1].path("content").asText())
        assertEquals(1, user.path("contexts").size())
        assertEquals(2, user.path("contexts")[0].path("occurrences")[0].path("targetKeys").size())
        assertEquals(2, user.path("bibliographicMetadata").path("references").size())
        assertFalse(requests.single().contains("rawParserOutput"))
        assertFalse(requests.single().contains("rawText"))
        assertFalse(requests.single().contains("full Source Document"))
    }

    @Test
    fun `preserves a model response with no selected Citation Targets`() = withServer(selectTargets = false) { server, _ ->
        val settings = localSettings(server)
        val catalog = ProviderCatalog.safeDefaults(openAiCompatibleClaimAnalysisSettings = settings)
        val provider = provider(settings, catalog)
        val request = requestFactory().from(documentWithOneContextAndTwoTargets())

        val result = provider.analyze(request, configuration(catalog))

        assertTrue(result.single().claims.single().citationTargetKeys.isEmpty())
    }

    @Test
    fun `batches whole contexts deterministically and rejects an oversized context before sending any request`() = withServer { server, requests ->
        val initialSettings = localSettings(server)
        val request = requestFactory().from(documentWithTwoContexts())
        val payloadFactory = OpenAiCompatibleClaimAnalysisPayloadFactory(objectMapper)
        val chatClient = OpenAiCompatibleChatClient(initialSettings, objectMapper)
        val singleContextBudgets = request.contexts.map { context ->
            val payload = payloadFactory.create(ClaimAnalysisRequest(listOf(context)))
            chatClient.requestBody(
                initialSettings.modelId,
                OpenAiCompatibleClaimAnalysisPrompt.systemPrompt,
                payloadFactory.userJson(payload),
            ).size
        }
        val responseReserve = 512
        val contextBudget = singleContextBudgets.max() + responseReserve
        val settings = initialSettings.copy(
            contextWindowTokens = contextBudget,
            maxCompletionTokens = responseReserve,
        )
        val providerCatalog = ProviderCatalog.safeDefaults(openAiCompatibleClaimAnalysisSettings = settings)
        val provider = provider(settings, providerCatalog)

        val result = provider.analyze(request, configuration(providerCatalog))

        assertEquals(2, result.size)
        assertEquals(2, requests.size)
        val sentContexts = requests.map { serialized ->
            val chat = objectMapper.readTree(serialized)
            objectMapper.readTree(chat.path("messages")[1].path("content").asText()).path("contexts").single()
                .path("contextStartOffset").asInt()
        }
        assertEquals(request.contexts.map(ClaimAnalysisContextInput::contextStartOffset), sentContexts)

        requests.clear()
        val tooSmall = settings.copy(contextWindowTokens = responseReserve + 512)
        val tooSmallCatalog = ProviderCatalog.safeDefaults(openAiCompatibleClaimAnalysisSettings = tooSmall)
        val tooSmallProvider = provider(tooSmall, tooSmallCatalog)
        val failure = assertThrows(OpenAiCompatibleProviderException::class.java) {
            tooSmallProvider.analyze(request, configuration(tooSmallCatalog))
        }
        assertTrue(failure.message.orEmpty().contains("Citation Context exceeds"))
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `external calls require explicit consent for both actual data categories before the send callback`() {
        val settings = OpenAiCompatibleClaimAnalysisSettings(
            enabled = true,
            baseUrl = "https://model.example.test/v1",
            modelId = "reviewed-model",
            trustedHosts = setOf("localhost"),
            enablementReviewed = true,
            retentionDisclosure = "The deployment reviewed this provider's retention terms.",
        )
        val catalog = ProviderCatalog.safeDefaults(openAiCompatibleClaimAnalysisSettings = settings)
        val categories = listOf(DataCategory.CITATION_CONTEXT.id, DataCategory.BIBLIOGRAPHIC_METADATA.id)
        val configuration = configuration(
            catalog,
            listOf(ExternalProviderConsentSnapshot(OpenAiCompatibleClaimAnalysisSettings.PROVIDER_ID, categories)),
        )
        val payload = OpenAiCompatibleClaimAnalysisPayloadFactory(objectMapper)
            .create(requestFactory().from(documentWithOneContextAndTwoTargets()))
        val gate = ProviderCallGate(catalog)
        var sent = false

        assertThrows(ProviderCallRejectedException::class.java) {
            gate.call(
                CLAIM_EXTRACTOR_ROLE,
                OpenAiCompatibleClaimAnalysisSettings.PROVIDER_ID,
                payload,
                configuration.copy(externalProviderConsents = emptyList()),
            ) { sent = true }
        }
        assertFalse(sent)
        assertEquals("approved", gate.call(
            CLAIM_EXTRACTOR_ROLE,
            OpenAiCompatibleClaimAnalysisSettings.PROVIDER_ID,
            payload,
            configuration,
        ) { authorized ->
            sent = true
            assertEquals(setOf(DataCategory.CITATION_CONTEXT, DataCategory.BIBLIOGRAPHIC_METADATA), authorized.dataCategories)
            "approved"
        })
        assertTrue(sent)
    }

    @Test
    fun `direct transport calls reject an unselectable endpoint before network access`() {
        withServer { server, requests ->
            val settings = localSettings(server).copy(enabled = false)
            val client = OpenAiCompatibleChatClient(settings, objectMapper)

            val failure = assertThrows(OpenAiCompatibleProviderException::class.java) {
                client.complete(byteArrayOf())
            }

            assertTrue(failure.message.orEmpty().contains("not selectable"))
            assertTrue(requests.isEmpty())
        }
    }

    @Test
    fun `rejects malformed responses and unknown target keys without exposing model content`() {
        val request = requestFactory().from(documentWithOneContextAndTwoTargets())
        val context = request.contexts.single()
        val invalidTargetContent = objectMapper.createObjectNode().apply {
            set<JsonNode>("contexts", objectMapper.createArrayNode().apply {
                add(objectMapper.createObjectNode().apply {
                    put("contextStartOffset", context.contextStartOffset)
                    put("contextEndOffset", context.contextEndOffset)
                    set<JsonNode>("claims", objectMapper.createArrayNode().add(
                        objectMapper.createObjectNode().apply {
                            put("text", "Treatment reduced pain")
                            put("sourceStartOffset", context.contextStartOffset)
                            put("sourceEndOffset", context.contextStartOffset + 5)
                            set<JsonNode>("citationTargetKeys", objectMapper.createArrayNode().add("unknown-target"))
                        },
                    ))
                })
            })
        }.toString()

        listOf("provider-private-response", "{\"contexts\":[]}", invalidTargetContent).forEach { content ->
            withServer(response = chatResponse(content)) { server, _ ->
                val clientSettings = localSettings(server)
                val providerCatalog = ProviderCatalog.safeDefaults(openAiCompatibleClaimAnalysisSettings = clientSettings)
                val provider = provider(clientSettings, providerCatalog)
                val failure = assertThrows(OpenAiCompatibleProviderException::class.java) {
                    provider.analyze(request, configuration(providerCatalog))
                }

                assertFalse(failure.message.orEmpty().contains("provider-private-response"))
            }
        }
    }

    @Test
    fun `request timeout remains active while the response body is streaming`() {
        val responseStarted = CountDownLatch(1)
        val releaseResponse = CountDownLatch(1)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/chat/completions") { exchange ->
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.use { output ->
                output.write("{".toByteArray(StandardCharsets.UTF_8))
                output.flush()
                responseStarted.countDown()
                releaseResponse.await(3, TimeUnit.SECONDS)
                runCatching { output.write("}".toByteArray(StandardCharsets.UTF_8)) }
            }
        }
        server.start()
        try {
            val settings = localSettings(server).copy(requestTimeoutMillis = 1_000)
            val client = OpenAiCompatibleChatClient(settings, objectMapper)
            val requestBody = client.requestBody(settings.modelId, "system", "{}")

            val failure = assertThrows(RetryableOpenAiCompatibleProviderException::class.java) {
                client.complete(requestBody)
            }

            assertTrue(responseStarted.await(2, TimeUnit.SECONDS))
            assertTrue(failure.message.orEmpty().contains("timed out"))
        } finally {
            releaseResponse.countDown()
            server.stop(0)
        }
    }

    @Test
    fun `retries provider throttling but treats client errors as permanent without exposing response content`() {
        withServer(response = "provider-private-response", statusCode = 429) { server, _ ->
            val settings = localSettings(server)
            val client = OpenAiCompatibleChatClient(settings, objectMapper)
            val failure = assertThrows(RetryableOpenAiCompatibleProviderException::class.java) {
                client.complete(client.requestBody(settings.modelId, "system", "{}"))
            }
            assertFalse(failure.message.orEmpty().contains("provider-private-response"))
        }
        withServer(response = "provider-private-response", statusCode = 400) { server, _ ->
            val settings = localSettings(server)
            val client = OpenAiCompatibleChatClient(settings, objectMapper)
            val failure = assertThrows(OpenAiCompatibleProviderException::class.java) {
                client.complete(client.requestBody(settings.modelId, "system", "{}"))
            }
            assertTrue(failure.message.orEmpty().contains("HTTP 400"))
            assertFalse(failure.message.orEmpty().contains("provider-private-response"))
        }
    }

    @Test
    fun `bounds provider response bytes and does not expose response content in failures`() = withServer(
        response = "provider-private-response".repeat(100),
    ) { server, _ ->
        val settings = localSettings(server).copy(maxResponseBytes = 128)
        val client = OpenAiCompatibleChatClient(settings, objectMapper)
        val requestBody = client.requestBody(settings.modelId, "system", "{}")

        val failure = assertThrows(OpenAiCompatibleProviderException::class.java) {
            client.complete(requestBody)
        }

        assertTrue(failure.message.orEmpty().contains("response exceeds"))
        assertFalse(failure.message.orEmpty().contains("provider-private-response"))
    }

    private fun provider(
        settings: OpenAiCompatibleClaimAnalysisSettings,
        catalog: ProviderCatalog,
    ) = OpenAiCompatibleClaimAnalysisProvider(
        settings = settings,
        providerCallGate = ProviderCallGate(catalog),
        chatClient = OpenAiCompatibleChatClient(settings, objectMapper),
        payloadFactory = OpenAiCompatibleClaimAnalysisPayloadFactory(objectMapper),
        objectMapper = objectMapper,
    )

    private fun configuration(
        catalog: ProviderCatalog,
        consents: List<ExternalProviderConsentSnapshot> = emptyList(),
    ) = RunConfigurationFactory(
        objectMapper = objectMapper,
        providerCatalog = catalog,
        parserId = "grobid",
        parserVersion = "0.9.1-crf",
        languageDetectorVersion = "0.6",
        limits = ValidationLimitsSnapshot(1_000_000, 20, 100_000, 100_000, 100, 0.65),
    ).from(
        RunConfigurationRequest(
            claimExtractorProvider = OpenAiCompatibleClaimAnalysisSettings.PROVIDER_ID,
            externalProviderConsents = consents,
        ),
    )

    private fun localSettings(server: HttpServer) = OpenAiCompatibleClaimAnalysisSettings(
        enabled = true,
        baseUrl = "http://127.0.0.1:${server.address.port}/v1",
        modelId = "fixture-model",
        trustedHosts = setOf("127.0.0.1"),
    )

    private fun requestFactory() = ClaimAnalysisRequestFactory()

    private fun documentWithOneContextAndTwoTargets(): ParsedScientificDocument {
        val text = "Treatment reduced pain [1, 2]."
        val markerStart = text.indexOf("[1, 2]")
        return ParsedScientificDocument(
            parserId = "grobid",
            parserVersion = "0.9.1-crf",
            normalizedSourceText = text,
            sections = listOf(ParsedSection(0, "Results", text, 0, text.length)),
            citationContexts = listOf(
                ParsedCitationContext(
                    sectionOrder = 0,
                    boundaryKind = "SENTENCE_FALLBACK",
                    text = text,
                    startOffset = 0,
                    endOffset = text.length,
                    occurrences = listOf(ParsedCitationOccurrence("[1, 2]", markerStart, markerStart + 6, listOf("ref1", "ref2"))),
                ),
            ),
            bibliographyEntries = bibliographyEntries(),
        )
    }

    private fun documentWithTwoContexts(): ParsedScientificDocument {
        val text = "First claim [1]. Second claim [2]."
        val firstMarker = text.indexOf("[1]")
        val secondContextStart = text.indexOf("Second claim")
        val secondMarker = text.indexOf("[2]")
        return ParsedScientificDocument(
            parserId = "grobid",
            parserVersion = "0.9.1-crf",
            normalizedSourceText = text,
            sections = listOf(ParsedSection(0, "Results", text, 0, text.length)),
            citationContexts = listOf(
                ParsedCitationContext(
                    0,
                    "CLAUSE",
                    text.substring(0, secondContextStart - 1),
                    0,
                    secondContextStart - 1,
                    listOf(ParsedCitationOccurrence("[1]", firstMarker, firstMarker + 3, listOf("ref1"))),
                ),
                ParsedCitationContext(
                    0,
                    "CLAUSE",
                    text.substring(secondContextStart),
                    secondContextStart,
                    text.length,
                    listOf(ParsedCitationOccurrence("[2]", secondMarker, secondMarker + 3, listOf("ref2"))),
                ),
            ),
            bibliographyEntries = bibliographyEntries(),
        )
    }

    private fun bibliographyEntries() = listOf(
        ParsedBibliographyEntry(0, "ref1", "Reference one", "Reference one", listOf("Ada Example"), 2024, "10.1000/one", "JOURNAL_ARTICLE"),
        ParsedBibliographyEntry(1, "ref2", "Reference two", "Reference two", listOf("Grace Example"), 2023, "10.1000/two", "JOURNAL_ARTICLE"),
    )

    private fun withServer(
        response: String? = null,
        selectTargets: Boolean = true,
        statusCode: Int = 200,
        block: (HttpServer, CopyOnWriteArrayList<String>) -> Unit,
    ) {
        val requests = CopyOnWriteArrayList<String>()
        authorizationHeaders.clear()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/chat/completions") { exchange ->
            val body = exchange.requestBody.use { it.readBytes().toString(StandardCharsets.UTF_8) }
            requests += body
            authorizationHeaders += exchange.requestHeaders.getFirst("Authorization").orEmpty()
            val bodyToReturn = response ?: createChatCompletionResponse(body, selectTargets)
            val responseBytes = bodyToReturn.toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
            exchange.sendResponseHeaders(statusCode, responseBytes.size.toLong())
            exchange.responseBody.use { it.write(responseBytes) }
        }
        server.start()
        try {
            block(server, requests)
        } finally {
            server.stop(0)
        }
    }

    private fun chatResponse(content: String): String = objectMapper.writeValueAsString(
        mapOf(
            "choices" to listOf(
                mapOf(
                    "finish_reason" to "stop",
                    "message" to mapOf("role" to "assistant", "content" to content),
                ),
            ),
        ),
    )

    private fun createChatCompletionResponse(requestBody: String, selectTargets: Boolean): String {
        val request = objectMapper.readTree(requestBody)
        val user = objectMapper.readTree(request.path("messages")[1].path("content").asText())
        val contexts = objectMapper.createArrayNode()
        user.path("contexts").forEach { context ->
            val contextStart = context.path("contextStartOffset").asInt()
            val contextEnd = context.path("contextEndOffset").asInt()
            val targetKeys = mutableListOf<String>()
            context.path("occurrences").forEach { occurrence ->
                occurrence.path("targetKeys").forEach { key -> targetKeys += key.asText() }
            }
            val claim = objectMapper.createObjectNode()
                .put("text", "Validated atomic claim")
                .put("sourceStartOffset", contextStart)
                .put("sourceEndOffset", contextStart + 5)
            claim.set<JsonNode>("citationTargetKeys", objectMapper.createArrayNode().apply {
                if (selectTargets) targetKeys.firstOrNull()?.let(::add)
            })
            contexts.add(
                objectMapper.createObjectNode()
                    .put("contextStartOffset", contextStart)
                    .put("contextEndOffset", contextEnd)
                    .set<JsonNode>("claims", objectMapper.createArrayNode().add(claim)),
            )
        }
        val content = objectMapper.createObjectNode().apply { set<JsonNode>("contexts", contexts) }
        val message = objectMapper.createObjectNode()
            .put("role", "assistant")
            .put("content", objectMapper.writeValueAsString(content))
        val choice = objectMapper.createObjectNode()
            .put("finish_reason", "stop")
            .set<JsonNode>("message", message)
        val result = objectMapper.createObjectNode().apply {
            set<JsonNode>("choices", objectMapper.createArrayNode().add(choice))
        }
        return objectMapper.writeValueAsString(result)
    }
}
