package com.papertrail.api.analysis.execution

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.papertrail.api.infrastructure.providers.openai.OpenAiCompatibleChatClient
import com.papertrail.api.infrastructure.providers.openai.OpenAiCompatibleEndpointSettings
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.net.InetSocketAddress
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
    fun `OpenAI transport artifacts come from actual request and response with sensitive message content omitted`() {
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
        assertTrue(artifacts.all { it.second.fidelity == CaptureFidelity.PARTIAL })
    }

    private fun sha256Hex(content: String): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(content.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
}
