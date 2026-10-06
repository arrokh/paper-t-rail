package com.papertrail.api.analysis.execution

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.papertrail.api.analysis.configuration.RunConfigurationFactory
import com.papertrail.api.analysis.configuration.ValidationLimitsSnapshot
import com.papertrail.api.analysis.http.RunConfigurationRequest
import com.papertrail.api.scholarly.references.client.CrossrefScholarlyMetadataLookup
import com.papertrail.api.scholarly.references.client.NoOpCrossrefLookupCache
import com.papertrail.api.infrastructure.providers.DataCategory
import com.papertrail.api.infrastructure.providers.ProviderCallGate
import com.papertrail.api.infrastructure.providers.configuredExternalProviderCatalog
import com.papertrail.api.infrastructure.providers.externalProviderConsent
import com.papertrail.api.infrastructure.providers.openai.OpenAiCompatibleChatClient
import com.papertrail.api.infrastructure.providers.openai.OpenAiCompatibleEndpointSettings
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

class AnalysisRunExecutionServiceTest {
    @Test
    fun `span persistence failure does not fail analysis or attach child artifacts to parent`() {
        val repository = Mockito.mock(AnalysisRunExecutionRepository::class.java)
        val service = AnalysisRunExecutionService(repository, ExecutionCaptureSanitizer(), ObjectMapper())
        val analysisRunId = UUID.randomUUID()
        val parentSpec = ExecutionSpanSpec("source", "INTERNAL", "Parent operation")
        val childSpec = ExecutionSpanSpec("source", "PROVIDER", "Provider operation")
        val parentHandle = ExecutionSpanHandle(UUID.randomUUID(), analysisRunId, UUID.randomUUID(), Instant.now(), System.nanoTime())
        Mockito.`when`(repository.startSpan(analysisRunId, parentSpec, "{}")).thenReturn(parentHandle)
        Mockito.`when`(repository.startSpan(analysisRunId, childSpec.copy(parentSpanId = parentHandle.id), "{}"))
            .thenThrow(IllegalStateException("trace storage unavailable"))

        var analysisCompleted = false
        val result = service.record(analysisRunId, parentSpec) {
            val childResult = service.record(analysisRunId, childSpec) {
                service.captureCurrent(
                    ExecutionSpanArtifactSpec("RESULT", "run-stage-result-v1", mapOf("status" to "SUCCEEDED")),
                )
                "analysis-result"
            }
            analysisCompleted = true
            childResult
        }

        assertTrue(analysisCompleted)
        assertEquals("analysis-result", result)
        assertTrue(Mockito.mockingDetails(repository).invocations.none { it.method.name == "recordArtifact" })
    }

    @Test
    fun `OpenAI transport artifacts retain safe summaries but omit prompt and answer content`() {
        val mapper = ObjectMapper()
        val requestBodyReceived = AtomicReference<String>()
        val privateResponse = """
            {"id":"response-private-id","object":"chat.completion","created":1710000000,
             "model":"gpt-public","choices":[{"index":0,"finish_reason":"stop",
             "message":{"role":"assistant","content":"participant P-0042 private@example.org"}}],
             "usage":{"prompt_tokens":12,"completion_tokens":5,"total_tokens":17}}
        """.trimIndent()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/chat/completions") { exchange ->
            requestBodyReceived.set(exchange.requestBody.use { it.readBytes().toString(Charsets.UTF_8) })
            val bytes = privateResponse.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        val repository = Mockito.mock(AnalysisRunExecutionRepository::class.java)
        val runId = UUID.randomUUID()
        val spec = ExecutionSpanSpec("source", "PROVIDER", "Analyze Atomic Claims")
        val handle = ExecutionSpanHandle(UUID.randomUUID(), runId, UUID.randomUUID(), Instant.now(), System.nanoTime())
        Mockito.`when`(repository.startSpan(runId, spec, "{}")).thenReturn(handle)
        val artifacts = mutableListOf<Pair<String, SanitizedExecutionArtifact>>()
        val sanitizer = ExecutionCaptureSanitizer()
        val service = AnalysisRunExecutionService(repository, sanitizer, mapper)
        val settings = OpenAiCompatibleEndpointSettings(
            enabled = true,
            baseUrl = "http://127.0.0.1:${server.address.port}",
            trustedHosts = setOf("127.0.0.1"),
        )
        val client = OpenAiCompatibleChatClient(settings, mapper, service)
        val requestBody = client.requestBody(
            modelId = "gpt-public",
            maxCompletionTokens = 64,
            responseFormat = mapper.readTree("""{"type":"json_schema","json_schema":{"name":"claims"}}""") as JsonNode,
            systemPrompt = "private system prompt",
            userJson = "participant P-0042 private@example.org apiKey=sk-private-value",
        )
        val expectedArtifacts = listOf(
            ExecutionSpanArtifactSpec("REQUEST", "openai-compatible-request-v1", emptyMap()) to sanitizer.sanitizeOpenAiRequestBody(requestBody),
            ExecutionSpanArtifactSpec("RESPONSE", "openai-compatible-response-v1", emptyMap()) to sanitizer.sanitizeOpenAiResponseBody(privateResponse.toByteArray()),
        )
        expectedArtifacts.forEach { (artifact, sanitized) ->
            Mockito.doAnswer { invocation ->
                artifacts += invocation.getArgument<ExecutionSpanArtifactSpec>(2).role to
                    invocation.getArgument<SanitizedExecutionArtifact>(3)
                null
            }.`when`(repository).recordArtifact(
                runId,
                handle.id,
                artifact,
                sanitized,
                sha256Hex(sanitized.content!!),
            )
        }

        try {
            service.record(runId, spec) { client.complete(requestBody) }
        } finally {
            server.stop(0)
        }

        assertTrue(requestBodyReceived.get().contains("private@example.org"))
        assertEquals(setOf("REQUEST", "RESPONSE"), artifacts.map { it.first }.toSet())
        val storedContents = artifacts.mapNotNull { it.second.content }
        assertEquals(2, storedContents.size)
        assertTrue(storedContents.all { it.contains("gpt-public") })
        assertTrue(storedContents.none { it.contains("private@example.org") || it.contains("P-0042") || it.contains("sk-private-value") })
        assertTrue(storedContents.none { it.contains("private system prompt") || it.contains("response-private-id") })
        assertTrue(storedContents.any { it.contains("promptContentOmitted") })
        assertTrue(storedContents.any { it.contains("messageContentOmitted") })
        assertTrue(artifacts.all { it.second.fidelity == CaptureFidelity.PARTIAL })
    }

    @Test
    fun `Crossref network calls capture sanitized request and response details within provider spans`() {
        val mapper = jacksonObjectMapper()
        val catalog = configuredExternalProviderCatalog()
        val configuration = RunConfigurationFactory(
            objectMapper = mapper,
            providerCatalog = catalog,
            parserId = "grobid",
            parserVersion = "0.9.1-crf",
            languageDetectorVersion = "0.6",
            limits = ValidationLimitsSnapshot(1_000_000, 20, 100_000, 100_000, 100, 0.65),
        ).from(
            RunConfigurationRequest(
                scholarlyMetadataProvider = "crossref",
                externalProviderConsents = listOf(
                    externalProviderConsent(catalog, "crossref", listOf(DataCategory.BIBLIOGRAPHIC_METADATA.id)),
                ),
            ),
        )
        val builder = RestClient.builder().baseUrl("https://api.crossref.org")
        val server = MockRestServiceServer.bindTo(builder).build()
        server.expect(requestTo(containsString("https://api.crossref.org/works/10.1234")))
            .andExpect(method(HttpMethod.GET))
            .andRespond(withSuccess(
                """{"message":{"DOI":"10.1234/example","title":["Safe public title"],"author":[{"given":"Ada","family":"Researcher"}],"published-print":{"date-parts":[[2024]]}}}""",
                MediaType.APPLICATION_JSON,
            ))

        val repository = Mockito.mock(AnalysisRunExecutionRepository::class.java)
        val runId = UUID.randomUUID()
        val eventId = UUID.randomUUID()
        val parent = ExecutionSpanSpec("references", "INTERNAL", "Resolve bibliography entry", eventId = eventId)
        val parentHandle = ExecutionSpanHandle(UUID.randomUUID(), runId, UUID.randomUUID(), Instant.now(), System.nanoTime(), stageId = "references", eventId = eventId)
        val providerSpec = ExecutionSpanSpec(
            stageId = "references",
            kind = "PROVIDER",
            name = "Crossref scholarly metadata request",
            eventId = eventId,
            parentSpanId = parentHandle.id,
            providerId = "crossref",
            operationId = ExecutionOperationId.forEvent(eventId, "provider-call:crossref-doi-lookup"),
            attributes = mapOf("httpRoute" to "/works"),
        )
        val providerHandle = ExecutionSpanHandle(UUID.randomUUID(), runId, providerSpec.operationId!!, Instant.now(), System.nanoTime(), eventId = eventId)
        Mockito.`when`(repository.startSpan(runId, parent, "{}")).thenReturn(parentHandle)
        Mockito.`when`(repository.startSpan(runId, providerSpec, """{"httpRoute":"/works"}""")).thenReturn(providerHandle)
        val execution = AnalysisRunExecutionService(repository, ExecutionCaptureSanitizer(), mapper)
        val lookup = CrossrefScholarlyMetadataLookup(
            client = builder.build(),
            objectMapper = mapper,
            callGate = ProviderCallGate(catalog),
            configuration = configuration,
            contactEmail = null,
            cache = NoOpCrossrefLookupCache,
            executionService = execution,
        )
        val work = try {
            execution.record(runId, parent) { lookup.byDoi("10.1234/example") }
        } finally {
            server.verify()
        }

        assertEquals("10.1234/example", work?.doi)
        val recordedProviderSpec = Mockito.mockingDetails(repository).invocations
            .filter { it.method.name == "startSpan" }
            .map { it.arguments[1] as ExecutionSpanSpec }
            .single { it.name == "Crossref scholarly metadata request" }
        assertEquals("PROVIDER", recordedProviderSpec.kind)
        assertEquals("crossref", recordedProviderSpec.providerId)
        assertEquals("/works", recordedProviderSpec.attributes["httpRoute"])
        assertEquals(parentHandle.id, recordedProviderSpec.parentSpanId)
        val recordedArtifacts = Mockito.mockingDetails(repository).invocations
            .filter { it.method.name == "recordArtifact" }
            .map { it.arguments[2] as ExecutionSpanArtifactSpec to it.arguments[3] as SanitizedExecutionArtifact }
        assertEquals(listOf("REQUEST", "RESPONSE"), recordedArtifacts.map { it.first.role })
        val requestContent = recordedArtifacts[0].second.content!!
        val responseContent = recordedArtifacts[1].second.content!!
        assertTrue(requestContent.contains("10.1234/example"))
        assertTrue(responseContent.contains("10.1234/example"))
        assertTrue(responseContent.contains("Safe public title"))
        assertEquals(CaptureFidelity.COMPLETE, recordedArtifacts[0].second.fidelity)
        assertEquals(CaptureFidelity.PARTIAL, recordedArtifacts[1].second.fidelity)
    }

    private fun sha256Hex(content: String): String = MessageDigest.getInstance("SHA-256")
        .digest(content.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
}
