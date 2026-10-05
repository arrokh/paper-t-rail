package com.papertrail.api.citation.claims.provider

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.papertrail.api.analysis.http.ExternalProviderConsentRequest
import com.papertrail.api.analysis.configuration.RunConfigurationFactory
import com.papertrail.api.analysis.execution.AnalysisRunExecutionRepository
import com.papertrail.api.analysis.execution.AnalysisRunExecutionService
import com.papertrail.api.analysis.execution.CaptureFidelity
import com.papertrail.api.analysis.execution.ExecutionCaptureSanitizer
import com.papertrail.api.analysis.execution.ExecutionSpanArtifactSpec
import com.papertrail.api.analysis.execution.ExecutionSpanHandle
import com.papertrail.api.analysis.execution.ExecutionSpanSpec
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
import com.papertrail.api.infrastructure.providers.openai.OpenAiCompatibleChatClient
import com.papertrail.api.infrastructure.providers.openai.OpenAiCompatibleEndpointSettings
import com.papertrail.api.infrastructure.providers.openai.OpenAiCompatibleProviderException
import com.papertrail.api.infrastructure.providers.openai.RetryableOpenAiCompatibleProviderException
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.slf4j.LoggerFactory
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID
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
        val initialSettings = localSettings(server)
        val settings = initialSettings.copy(endpoint = initialSettings.endpoint.copy(apiKey = "server-side-secret"))
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
        val responseFormat = httpRequest.path("response_format")
        assertEquals("json_schema", responseFormat.path("type").asText())
        assertEquals("paper_trail_claim_analysis", responseFormat.path("json_schema").path("name").asText())
        assertTrue(responseFormat.path("json_schema").path("strict").asBoolean())
        val outputSchema = responseFormat.path("json_schema").path("schema")
        assertEquals(setOf("claims"), outputSchema.path("required").map { it.asText() }.toSet())
        val claimSchema = outputSchema.path("properties").path("claims").path("items")
        assertEquals(
            setOf("text", "sourceStartOffset", "sourceEndOffset", "citationTargetKeys"),
            claimSchema.path("required").map { it.asText() }.toSet(),
        )
        val allowedTargetKeys = request.contexts.single().targetCandidates.map { it.key.value }.toSet()
        val targetKeyEnum = claimSchema.path("properties").path("citationTargetKeys").path("items").path("enum")
        assertEquals(allowedTargetKeys, targetKeyEnum.map { it.asText() }.toSet())
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
    fun `real OpenAI claim provider records sanitized request response and typed result in one operation`() = withServer { server, requests ->
        val settings = localSettings(server)
        val catalog = ProviderCatalog.safeDefaults(openAiCompatibleClaimAnalysisSettings = settings)
        val repository = Mockito.mock(AnalysisRunExecutionRepository::class.java)
        val execution = AnalysisRunExecutionService(repository, ExecutionCaptureSanitizer(), objectMapper)
        val runId = UUID.randomUUID()
        val spanSpec = ExecutionSpanSpec("source", "PROVIDER", "Analyze Atomic Claims")
        val spanHandle = ExecutionSpanHandle(UUID.randomUUID(), runId, UUID.randomUUID(), Instant.now(), System.nanoTime())
        Mockito.`when`(repository.startSpan(runId, spanSpec, "{}")).thenReturn(spanHandle)
        val childOperationId = UUID.nameUUIDFromBytes("${spanHandle.operationId}:provider-call:openai-claim-batch-0".toByteArray())
        val childSpec = ExecutionSpanSpec(
            stageId = "source",
            kind = "PROVIDER",
            name = "OpenAI-compatible claim model call",
            attempt = 1,
            parentSpanId = spanHandle.id,
            providerId = OpenAiCompatibleEndpointSettings.PROVIDER_ID,
            modelId = settings.modelId,
            operationId = childOperationId,
            attributes = mapOf("httpRoute" to "/v1/chat/completions"),
        )
        val childHandle = ExecutionSpanHandle(
            UUID.randomUUID(), runId, childOperationId, Instant.now(), System.nanoTime(), "source", 1, null,
        )
        Mockito.`when`(repository.startSpan(runId, childSpec, """{"httpRoute":"/v1/chat/completions"}""")).thenReturn(childHandle)
        val provider = provider(settings, catalog, execution)
        val request = requestFactory().from(documentWithOneContextAndTwoTargets())
        val configuration = configuration(catalog)

        val analyzed = execution.record(runId, spanSpec) {
            execution.captureCurrent(
                ExecutionSpanArtifactSpec(
                    "INPUT",
                    "run-stage-input-v1",
                    mapOf("itemCount" to request.contexts.size, "candidateCount" to request.contexts.sumOf { it.targetCandidates.size }),
                ),
            )
            provider.analyze(request, configuration).also { result ->
                execution.captureCurrent(
                    ExecutionSpanArtifactSpec(
                        "RESULT",
                        "run-stage-result-v1",
                        mapOf("status" to "SUCCEEDED", "itemCount" to result.sumOf { it.claims.size }),
                    ),
                )
            }
        }

        assertEquals(1, analyzed.single().claims.size)
        assertTrue(requests.single().contains("Treatment reduced pain"))
        val captured = Mockito.mockingDetails(repository).invocations
            .filter { it.method.name == "recordArtifact" }
            .map {
                it.arguments[2] as ExecutionSpanArtifactSpec to
                    it.arguments[3] as com.papertrail.api.analysis.execution.SanitizedExecutionArtifact
            }
        assertEquals(listOf("INPUT", "REQUEST", "RESPONSE", "RESULT"), captured.map { it.first.role })
        assertEquals(CaptureFidelity.SANITIZED, captured.first().second.fidelity)
        assertEquals(CaptureFidelity.PARTIAL, captured[1].second.fidelity)
        assertEquals(CaptureFidelity.PARTIAL, captured[2].second.fidelity)
        assertEquals(CaptureFidelity.SANITIZED, captured.last().second.fidelity)
        val stored = captured.mapNotNull { it.second.content }
        assertTrue(stored.none { it.contains("Treatment reduced pain") || it.contains("systemPrompt") })
        assertTrue(stored[1].contains("promptContentOmitted"))
        assertTrue(stored[2].contains("messageContentOmitted"))
        assertTrue(stored.last().contains("\"itemCount\":1"))
    }

    @Test
    fun `availability preflight uses only the content-free OpenAI models endpoint`() = withModelsServer { server, requests ->
        val initialSettings = localSettings(server)
        val settings = initialSettings.copy(
            endpoint = initialSettings.endpoint.copy(
                baseUrl = "http://127.0.0.1:${server.address.port}",
                apiKey = "server-side-secret",
            ),
        )
        val catalog = ProviderCatalog.safeDefaults(openAiCompatibleClaimAnalysisSettings = settings)
        val provider = provider(settings, catalog)

        provider.validateAvailability(configuration(catalog))

        assertEquals(
            AvailabilityRequest("GET", "/v1/models", "Bearer server-side-secret", ""),
            requests.single(),
        )
    }

    @Test
    fun `availability preflight retries server failures and does not expose response content`() {
        withModelsServer(statusCode = 503) { server, _ ->
            val settings = localSettings(server)
            val catalog = ProviderCatalog.safeDefaults(openAiCompatibleClaimAnalysisSettings = settings)
            val failure = assertThrows(RetryableOpenAiCompatibleProviderException::class.java) {
                provider(settings, catalog).validateAvailability(configuration(catalog))
            }
            assertFalse(failure.message.orEmpty().contains("provider-private-response"))
        }
        withModelsServer(statusCode = 404) { server, _ ->
            val settings = localSettings(server)
            val catalog = ProviderCatalog.safeDefaults(openAiCompatibleClaimAnalysisSettings = settings)
            val failure = assertThrows(OpenAiCompatibleProviderException::class.java) {
                provider(settings, catalog).validateAvailability(configuration(catalog))
            }
            assertTrue(failure.message.orEmpty().contains("HTTP 404"))
            assertFalse(failure.message.orEmpty().contains("provider-private-response"))
        }
    }

    @Test
    fun `maps claims from one context without requiring model generated context identity`() {
        val request = requestFactory().from(documentWithOneContextAndTwoTargets())
        val context = request.contexts.single()
        val targetKey = context.targetCandidates.first().key.value
        val responseContent = objectMapper.writeValueAsString(
            mapOf(
                "claims" to listOf(
                    mapOf(
                        "text" to "Validated atomic claim",
                        "sourceStartOffset" to context.contextStartOffset,
                        "sourceEndOffset" to context.contextStartOffset + 5,
                        "citationTargetKeys" to listOf(targetKey),
                    ),
                ),
            ),
        )
        val response = chatResponse(responseContent)

        withServer(response = response) { server, _ ->
            val settings = localSettings(server)
            val catalog = ProviderCatalog.safeDefaults(openAiCompatibleClaimAnalysisSettings = settings)
            val result = provider(settings, catalog).analyze(request, configuration(catalog))

            assertEquals(context.contextStartOffset, result.single().contextStartOffset)
            assertEquals(context.contextEndOffset, result.single().contextEndOffset)
            assertEquals(listOf(context.targetCandidates.first().key), result.single().claims.single().citationTargetKeys)
        }
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
    fun `sends each Citation Context in a separate model request`() = withServer { server, requests ->
        val settings = localSettings(server)
        val catalog = ProviderCatalog.safeDefaults(openAiCompatibleClaimAnalysisSettings = settings)
        val request = requestFactory().from(documentWithTwoContexts())

        val result = provider(settings, catalog).analyze(request, configuration(catalog))

        assertEquals(2, result.size)
        assertEquals(2, requests.size)
        val contextCounts = requests.map { serialized ->
            val chat = objectMapper.readTree(serialized)
            objectMapper.readTree(chat.path("messages")[1].path("content").asText()).path("contexts").size()
        }
        assertEquals(listOf(1, 1), contextCounts)
    }

    @Test
    fun `batches whole contexts deterministically and rejects an oversized context before sending any request`() = withServer { server, requests ->
        val initialSettings = localSettings(server)
        val request = requestFactory().from(documentWithTwoContexts())
        val payloadFactory = OpenAiCompatibleClaimAnalysisPayloadFactory(objectMapper)
        val chatClient = OpenAiCompatibleChatClient(initialSettings.endpoint, objectMapper)
        val singleContextBudgets = request.contexts.map { context ->
            val payload = payloadFactory.create(ClaimAnalysisRequest(listOf(context)))
            chatClient.requestBody(
                modelId = initialSettings.modelId,
                maxCompletionTokens = initialSettings.maxCompletionTokens,
                responseFormat = OpenAiCompatibleClaimAnalysisResponseFormat.create(
                    objectMapper,
                    context.targetCandidates.map { it.key.value },
                ),
                systemPrompt = OpenAiCompatibleClaimAnalysisPrompt.systemPrompt,
                userJson = payloadFactory.userJson(payload),
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
            endpoint = OpenAiCompatibleEndpointSettings(
                enabled = true,
                baseUrl = "https://model.example.test/v1",
                trustedHosts = setOf("localhost"),
                retentionDisclosure = "Retention and deletion details are unknown; consult the provider's terms.",
            ),
            modelId = "reviewed-model",
        )
        val catalog = ProviderCatalog.safeDefaults(openAiCompatibleClaimAnalysisSettings = settings)
        val categories = listOf(DataCategory.CITATION_CONTEXT.id, DataCategory.BIBLIOGRAPHIC_METADATA.id)
        val disclosureFingerprint = requireNotNull(
            catalog.requireSelectable(CLAIM_EXTRACTOR_ROLE, OpenAiCompatibleClaimAnalysisSettings.PROVIDER_ID)
                .consentDisclosureFingerprint(),
        )
        val configuration = configuration(
            catalog,
            listOf(ExternalProviderConsentRequest(
                OpenAiCompatibleClaimAnalysisSettings.PROVIDER_ID,
                categories,
                disclosureFingerprint,
            )),
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
            val initialSettings = localSettings(server)
            val settings = initialSettings.copy(endpoint = initialSettings.endpoint.copy(enabled = false))
            val client = OpenAiCompatibleChatClient(settings.endpoint, objectMapper)

            val failure = assertThrows(OpenAiCompatibleProviderException::class.java) {
                client.complete(byteArrayOf())
            }

            assertTrue(failure.message.orEmpty().contains("not selectable"))
            assertTrue(requests.isEmpty())
        }
    }

    @Test
    fun `logs safe counts when dropping unrequested target keys without provider output`() {
        val logger = LoggerFactory.getLogger(OpenAiCompatibleClaimAnalysisProvider::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        try {
            val request = requestFactory().from(documentWithOneContextAndTwoTargets())
            val context = request.contexts.single()
            val privateClaimText = "do-not-log-provider-claim"
            val privateTargetKey = "do-not-log-provider-target"
            val invalidContent = objectMapper.writeValueAsString(
                mapOf(
                    "claims" to listOf(
                        mapOf(
                            "text" to privateClaimText,
                            "sourceStartOffset" to context.contextStartOffset,
                            "sourceEndOffset" to context.contextStartOffset + 5,
                            "citationTargetKeys" to listOf(privateTargetKey),
                        ),
                    ),
                ),
            )

            withServer(response = chatResponse(invalidContent)) { server, _ ->
                val settings = localSettings(server)
                val catalog = ProviderCatalog.safeDefaults(openAiCompatibleClaimAnalysisSettings = settings)
                val result = provider(settings, catalog).analyze(request, configuration(catalog))
                assertTrue(result.single().claims.single().citationTargetKeys.isEmpty())
            }

            val events = appender.list
            val rejection = events.single { it.message == "OpenAI-compatible claim-analysis target keys ignored" }
            val fields = rejection.keyValuePairs.associate { it.key to it.value }
            assertEquals("response_target_key_not_requested", fields["failureReasonCode"])
            assertEquals(1, fields["requestedContextCount"])
            assertEquals(1, fields["responseClaimCount"])
            assertEquals(context.targetCandidates.size, fields["requestedTargetKeyCount"])
            assertEquals(1, fields["responseTargetKeyCount"])
            assertEquals(1, fields["ignoredTargetKeyCount"])
            val loggedEvents = events.joinToString(" ") { it.formattedMessage + it.keyValuePairs }
            assertFalse(loggedEvents.contains(privateClaimText))
            assertFalse(loggedEvents.contains(privateTargetKey))
            assertFalse(loggedEvents.contains("sourceStartOffset"))
        } finally {
            logger.detachAppender(appender)
            appender.stop()
        }
    }

    @Test
    fun `drops unrequested target keys while preserving selected keys from the context`() {
        val request = requestFactory().from(documentWithOneContextAndTwoTargets())
        val context = request.contexts.single()
        val selectedTargetKey = context.targetCandidates.first().key
        val content = objectMapper.writeValueAsString(
            mapOf(
                "claims" to listOf(
                    mapOf(
                        "text" to "Treatment reduced pain",
                        "sourceStartOffset" to context.contextStartOffset,
                        "sourceEndOffset" to context.contextStartOffset + 5,
                        "citationTargetKeys" to listOf("unknown-target", selectedTargetKey.value),
                    ),
                ),
            ),
        )

        withServer(response = chatResponse(content)) { server, _ ->
            val settings = localSettings(server)
            val catalog = ProviderCatalog.safeDefaults(openAiCompatibleClaimAnalysisSettings = settings)

            val result = provider(settings, catalog).analyze(request, configuration(catalog))

            assertEquals(listOf(selectedTargetKey), result.single().claims.single().citationTargetKeys)
        }
    }

    @Test
    fun `rejects malformed responses without exposing model content`() {
        val request = requestFactory().from(documentWithOneContextAndTwoTargets())

        listOf("provider-private-response", "{\"contexts\":[]}").forEach { content ->
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
    fun `rejects duplicate JSON fields and trailing content in provider responses`() {
        val request = requestFactory().from(documentWithOneContextAndTwoTargets())
        val validContent = objectMapper.writeValueAsString(mapOf("claims" to emptyList<Any>()))
        val duplicateFieldContent = validContent.replaceFirst(
            "\"claims\":[]",
            "\"claims\":[],\"claims\":[]",
        )

        listOf("$validContent trailing content", duplicateFieldContent).forEach { invalidContent ->
            withServer(response = chatResponse(invalidContent)) { server, _ ->
                val settings = localSettings(server)
                val catalog = ProviderCatalog.safeDefaults(openAiCompatibleClaimAnalysisSettings = settings)
                val failure = assertThrows(OpenAiCompatibleProviderException::class.java) {
                    provider(settings, catalog).analyze(request, configuration(catalog))
                }

                assertFalse(failure.message.orEmpty().contains("trailing content"))
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
            val initialSettings = localSettings(server)
            val settings = initialSettings.copy(
                endpoint = initialSettings.endpoint.copy(requestTimeoutMillis = 1_000),
            )
            val client = OpenAiCompatibleChatClient(settings.endpoint, objectMapper)
            val requestBody = transportRequestBody(client, settings)

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
            val client = OpenAiCompatibleChatClient(settings.endpoint, objectMapper)
            val failure = assertThrows(RetryableOpenAiCompatibleProviderException::class.java) {
                client.complete(transportRequestBody(client, settings))
            }
            assertFalse(failure.message.orEmpty().contains("provider-private-response"))
        }
        withServer(response = "provider-private-response", statusCode = 400) { server, _ ->
            val settings = localSettings(server)
            val client = OpenAiCompatibleChatClient(settings.endpoint, objectMapper)
            val failure = assertThrows(OpenAiCompatibleProviderException::class.java) {
                client.complete(transportRequestBody(client, settings))
            }
            assertTrue(failure.message.orEmpty().contains("HTTP 400"))
            assertFalse(failure.message.orEmpty().contains("provider-private-response"))
        }
    }

    @Test
    fun `logs safe request metadata and response status without provider or request content`() {
        val logger = LoggerFactory.getLogger(OpenAiCompatibleChatClient::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        try {
            withServer(response = "provider-private-response", statusCode = 400) { server, _ ->
                val settings = localSettings(server)
                val client = OpenAiCompatibleChatClient(settings.endpoint, objectMapper)
                val requestBody = client.requestBody(
                    modelId = settings.modelId,
                    maxCompletionTokens = settings.maxCompletionTokens,
                    responseFormat = OpenAiCompatibleClaimAnalysisResponseFormat.create(objectMapper),
                    systemPrompt = "synthetic system content",
                    userJson = "{\"syntheticClaim\":\"do-not-log\"}",
                )

                assertThrows(OpenAiCompatibleProviderException::class.java) {
                    client.complete(
                        requestBody = requestBody,
                        modelId = settings.modelId,
                        responseFormatType = "json_schema",
                        responseFormatName = "paper_trail_claim_analysis",
                    )
                }
            }

            val events = appender.list
            val failureEvent = events.single { it.message == "OpenAI-compatible HTTP request returned a failure status" }
            val fields = failureEvent.keyValuePairs.associate { it.key to it.value }
            assertEquals(400, fields["httpStatus"])
            assertEquals("fixture-model", fields["modelId"])
            assertEquals("json_schema", fields["responseFormatType"])
            assertEquals("paper_trail_claim_analysis", fields["responseFormatName"])
            assertTrue(fields.containsKey("requestBytes"))
            assertTrue(fields.containsKey("responseBytes"))
            val loggedEvents = events.joinToString(" ") { it.formattedMessage + it.keyValuePairs }
            assertFalse(loggedEvents.contains("synthetic system content"))
            assertFalse(loggedEvents.contains("do-not-log"))
            assertFalse(loggedEvents.contains("provider-private-response"))
        } finally {
            logger.detachAppender(appender)
            appender.stop()
        }
    }

    @Test
    fun `logs successful chat status at info with safe metadata without request or response content`() {
        val logger = LoggerFactory.getLogger(OpenAiCompatibleChatClient::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        try {
            withServer(response = "provider-private-response", statusCode = 200) { server, _ ->
                val settings = localSettings(server)
                val client = OpenAiCompatibleChatClient(settings.endpoint, objectMapper)
                val requestBody = client.requestBody(
                    modelId = settings.modelId,
                    maxCompletionTokens = settings.maxCompletionTokens,
                    responseFormat = OpenAiCompatibleClaimAnalysisResponseFormat.create(objectMapper),
                    systemPrompt = "synthetic system content",
                    userJson = "{\"syntheticClaim\":\"do-not-log\"}",
                )

                assertEquals(
                    "provider-private-response",
                    client.complete(
                        requestBody = requestBody,
                        modelId = settings.modelId,
                        responseFormatType = "json_schema",
                        responseFormatName = "paper_trail_claim_analysis",
                    ),
                )
            }

            val events = appender.list
            val successEvent = events.single { it.message == "OpenAI-compatible HTTP request succeeded" }
            val fields = successEvent.keyValuePairs.associate { it.key to it.value }
            assertEquals("INFO", successEvent.level.toString())
            assertEquals(200, fields["httpStatus"])
            assertEquals("fixture-model", fields["modelId"])
            assertEquals("json_schema", fields["responseFormatType"])
            assertEquals("paper_trail_claim_analysis", fields["responseFormatName"])
            assertTrue((fields["requestBytes"] as Number).toInt() > 0)
            assertTrue((fields["responseBytes"] as Number).toInt() > 0)
            assertTrue((fields["durationMs"] as Number).toLong() >= 0L)
            val loggedEvents = events.joinToString(" ") { it.formattedMessage + it.keyValuePairs }
            assertFalse(loggedEvents.contains("synthetic system content"))
            assertFalse(loggedEvents.contains("do-not-log"))
            assertFalse(loggedEvents.contains("provider-private-response"))
        } finally {
            logger.detachAppender(appender)
            appender.stop()
        }
    }

    @Test
    fun `bounds provider response bytes and does not expose response content in failures`() = withServer(
        response = "provider-private-response".repeat(100),
    ) { server, _ ->
        val initialSettings = localSettings(server)
        val settings = initialSettings.copy(
            endpoint = initialSettings.endpoint.copy(maxResponseBytes = 128),
        )
        val client = OpenAiCompatibleChatClient(settings.endpoint, objectMapper)
        val requestBody = transportRequestBody(client, settings)

        val failure = assertThrows(OpenAiCompatibleProviderException::class.java) {
            client.complete(requestBody)
        }

        assertTrue(failure.message.orEmpty().contains("response exceeds"))
        assertFalse(failure.message.orEmpty().contains("provider-private-response"))
    }

    private fun transportRequestBody(
        client: OpenAiCompatibleChatClient,
        settings: OpenAiCompatibleClaimAnalysisSettings,
    ): ByteArray = client.requestBody(
        modelId = settings.modelId,
        maxCompletionTokens = settings.maxCompletionTokens,
        responseFormat = objectMapper.createObjectNode().put("type", "json_object"),
        systemPrompt = "system",
        userJson = "{}",
    )

    private fun provider(
        settings: OpenAiCompatibleClaimAnalysisSettings,
        catalog: ProviderCatalog,
        executionService: AnalysisRunExecutionService? = null,
    ) = OpenAiCompatibleClaimAnalysisProvider(
        settings = settings,
        providerCallGate = ProviderCallGate(catalog),
        chatClient = OpenAiCompatibleChatClient(settings.endpoint, objectMapper, executionService),
        payloadFactory = OpenAiCompatibleClaimAnalysisPayloadFactory(objectMapper),
        objectMapper = objectMapper,
        executionService = executionService,
    )

    private fun configuration(
        catalog: ProviderCatalog,
        consents: List<ExternalProviderConsentRequest> = emptyList(),
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
        endpoint = OpenAiCompatibleEndpointSettings(
            enabled = true,
            baseUrl = "http://127.0.0.1:${server.address.port}/v1",
            trustedHosts = setOf("127.0.0.1"),
        ),
        modelId = "fixture-model",
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

    private fun withModelsServer(
        statusCode: Int = 200,
        block: (HttpServer, CopyOnWriteArrayList<AvailabilityRequest>) -> Unit,
    ) {
        val requests = CopyOnWriteArrayList<AvailabilityRequest>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/models") { exchange ->
            val body = exchange.requestBody.use { it.readBytes().toString(StandardCharsets.UTF_8) }
            requests += AvailabilityRequest(
                method = exchange.requestMethod,
                path = exchange.requestURI.path,
                authorization = exchange.requestHeaders.getFirst("Authorization").orEmpty(),
                body = body,
            )
            val response = "provider-private-response".toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(statusCode, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        server.start()
        try {
            block(server, requests)
        } finally {
            server.stop(0)
        }
    }

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

    private data class AvailabilityRequest(
        val method: String,
        val path: String,
        val authorization: String,
        val body: String,
    )

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
        val context = user.path("contexts").single()
        val contextStart = context.path("contextStartOffset").asInt()
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
        val content = objectMapper.createObjectNode().apply {
            set<JsonNode>("claims", objectMapper.createArrayNode().add(claim))
        }
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
