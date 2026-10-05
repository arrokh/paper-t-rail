package com.papertrail.api.analysis.execution

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ExecutionCaptureSanitizerTest {
    private val sanitizer = ExecutionCaptureSanitizer()

    @Test
    fun `actual OpenAI request body retains only bounded transport metadata and omits prompts and schema`() {
        val body = """
            {"model":"gpt-public","temperature":0,"max_tokens":64,"stream":false,
             "response_format":{"type":"json_schema","json_schema":{"name":"claims-private"}},
             "messages":[{"role":"system","content":"safe prompt"},
                         {"role":"user","content":"participant P-0042 private@example.org apiKey=sk-private-value"}]}
        """.trimIndent().toByteArray()

        val result = sanitizer.sanitizeOpenAiRequestBody(body)

        assertEquals(CaptureFidelity.PARTIAL, result.fidelity)
        assertEquals("PROMPT_AND_SCHEMA_CONTENT_OMITTED", result.reason)
        assertTrue(result.content!!.contains("\"model\":\"gpt-public\""))
        assertTrue(result.content.contains("\"promptContentOmitted\":true"))
        assertFalse(result.content.contains("private@example.org"))
        assertFalse(result.content.contains("sk-private-value"))
        assertFalse(result.content.contains("P-0042"))
        assertFalse(result.content.contains("safe prompt"))
        assertFalse(result.content.contains("claims-private"))
    }

    @Test
    fun `actual System One request body retains safe structure while omitting claim and evidence text`() {
        val body = """
            {"model":"jev-system-one-v1","state":{"claim":"participant P-0042 private@example.org",
             "evidence":"apiKey=sk-private-value","section":"Results"},"questions":{"q1":{"type":"choice"}}}
        """.trimIndent().toByteArray()

        val result = sanitizer.sanitizeSystemOneRequestBody(body)

        assertEquals(CaptureFidelity.PARTIAL, result.fidelity)
        assertEquals("CLAIM_AND_EVIDENCE_TEXT_OMITTED", result.reason)
        assertTrue(result.content!!.contains("jev-system-one-v1"))
        assertTrue(result.content.contains("claimAndEvidenceTextOmitted"))
        assertFalse(result.content.contains("private@example.org"))
        assertFalse(result.content.contains("P-0042"))
        assertFalse(result.content.contains("sk-private-value"))
        assertFalse(result.content.contains("Results"))
    }

    @Test
    fun `actual OpenAI response retains safe metadata while omitting generated content`() {
        val body = """
            {"id":"response-private-id","object":"chat.completion","created":1710000000,
             "model":"gpt-public","choices":[{"index":0,"finish_reason":"stop",
             "message":{"role":"assistant","content":"participant P-0042 private@example.org"}}],
             "usage":{"prompt_tokens":12,"completion_tokens":5,"total_tokens":17},
             "debug":"private@example.org"}
        """.trimIndent().toByteArray()

        val result = sanitizer.sanitizeOpenAiResponseBody(body)

        assertEquals(CaptureFidelity.PARTIAL, result.fidelity)
        assertEquals("GENERATED_MESSAGE_CONTENT_OMITTED", result.reason)
        assertTrue(result.content!!.contains("\"total_tokens\":17"))
        assertFalse(result.content.contains("private@example.org"))
        assertFalse(result.content.contains("P-0042"))
        assertFalse(result.content.contains("response-private-id"))
        assertFalse(result.content.contains("debug"))
    }

    @Test
    fun `actual Jev response retains only bounded answer and usage metadata`() {
        val body = """
            {"model":"jev-model","answers":{"judgement":{"choice":"DIRECT_SUPPORT",
             "privateExplanation":"participant P-0042 private@example.org"},"evidence_role":{"choice":"PRIMARY_FINDING"}},
             "usage":{"input_tokens":10,"output_tokens":4},"diagnostic":"sk-private-value"}
        """.trimIndent().toByteArray()

        val result = sanitizer.sanitizeJevResponseBody(body)

        assertEquals(CaptureFidelity.PARTIAL, result.fidelity)
        assertEquals("ANSWER_CONTENT_OMITTED", result.reason)
        assertTrue(result.content!!.contains("jev-model"))
        assertTrue(result.content.contains("input_tokens"))
        assertFalse(result.content.contains("DIRECT_SUPPORT"))
        assertFalse(result.content.contains("privateExplanation"))
        assertFalse(result.content.contains("private@example.org"))
        assertFalse(result.content.contains("sk-private-value"))
    }

    @Test
    fun `unrecognized OpenAI response structure is omitted`() {
        val result = sanitizer.sanitizeOpenAiResponseBody(
            """{"error":{"message":"private@example.org"}}""".toByteArray(),
        )

        assertEquals(CaptureFidelity.OMITTED, result.fidelity)
        assertEquals("UNSAFE_UNSTRUCTURED_CONTENT", result.reason)
        assertEquals(null, result.content)
    }

    @Test
    fun `known structured metadata is retained without arbitrary payload fields`() {
        val result = sanitizer.sanitize(
            "provider-call-summary-v1",
            mapOf(
                "providerId" to "openai-compatible-chat",
                "modelId" to "gpt-example",
                "httpStatus" to 200,
                "requestBytes" to 128,
                "responseBytes" to 256,
                "elapsedMillis" to 42,
            ),
        )

        assertEquals(CaptureFidelity.SANITIZED, result.fidelity)
        assertTrue(result.content!!.contains("\"providerId\":\"openai-compatible-chat\""))
        assertFalse(result.content.contains("Authorization"))
    }

    @Test
    fun `unknown text and credential-like fields are omitted rather than persisted`() {
        val result = sanitizer.sanitize(
            "provider-call-summary-v1",
            mapOf(
                "providerId" to "provider",
                "prompt" to "patient contact me at private@example.org",
                "apiKey" to "sk-test-secret",
            ),
        )

        assertEquals(CaptureFidelity.OMITTED, result.fidelity)
        assertEquals("UNSUPPORTED_OR_UNSAFE_FIELDS", result.reason)
        assertEquals(null, result.content)
    }

    @Test
    fun `the recorded fixture provider ID is not mistaken for a private record identifier`() {
        assertTrue(sanitizer.isSafeIdentifier("recorded-fixtures"))
        assertFalse(sanitizer.isSafeIdentifier("record P-0042"))
    }

    @Test
    fun `HTTP routes omit hosts queries and path traversal`() {
        assertTrue(sanitizer.isSafeHttpRoute("/v1/chat/completions"))
        assertFalse(sanitizer.isSafeHttpRoute("//example.org/works"))
        assertFalse(sanitizer.isSafeHttpRoute("/works?doi=10.1234/example"))
        assertFalse(sanitizer.isSafeHttpRoute("/works/../private"))
    }

    @Test
    fun `credential-shaped model metadata is omitted`() {
        val result = sanitizer.sanitize(
            "provider-call-summary-v1",
            mapOf("providerId" to "provider", "modelId" to "sk-test-secret-value"),
        )

        assertEquals(CaptureFidelity.OMITTED, result.fidelity)
        assertEquals("AUTHENTICATION_SECRET", result.reason)
        assertEquals(null, result.content)
    }

    @Test
    fun `unstructured document request and parser response bodies are always omitted`() {
        val request = sanitizer.sanitize("source-document-pdf-request-v1", mapOf("content" to "private paper text"))
        val response = sanitizer.sanitize("source-parser-response-v1", mapOf("tei" to "<TEI>private contact</TEI>"))

        assertEquals(CaptureFidelity.OMITTED, request.fidelity)
        assertEquals(CaptureFidelity.OMITTED, response.fidelity)
        assertEquals("UNSAFE_UNSTRUCTURED_CONTENT", request.reason)
        assertEquals(null, request.content)
        assertEquals(null, response.content)
    }

    @Test
    fun `oversized structured artifact is omitted with an explicit reason`() {
        val result = ExecutionCaptureSanitizer(maxArtifactBytes = 10).sanitize(
            "provider-call-summary-v1",
            mapOf("providerId" to "provider"),
        )

        assertEquals(CaptureFidelity.OMITTED, result.fidelity)
        assertEquals("ARTIFACT_TOO_LARGE", result.reason)
        assertEquals(null, result.content)
    }

    @Test
    fun `public scholarly attribution is preserved while private contact details are rejected`() {
        val attribution = sanitizer.sanitize(
            "citation-metadata-v1",
            mapOf(
                "title" to "Evidence-guided analysis",
                "doi" to "10.1234/example.1",
                "authors" to listOf("Ada Example"),
                "publicationYear" to 2024,
            ),
        )
        val privateContact = sanitizer.sanitize(
            "citation-metadata-v1",
            mapOf(
                "title" to "Evidence-guided analysis",
                "doi" to "10.1234/example.1",
                "authors" to listOf("ada@example.org"),
                "publicationYear" to 2024,
            ),
        )
        val participantIdentifier = sanitizer.sanitize(
            "citation-metadata-v1",
            mapOf(
                "title" to "Evidence-guided analysis",
                "doi" to "10.1234/example.1",
                "authors" to listOf("participant ID-0042"),
                "publicationYear" to 2024,
            ),
        )

        assertEquals(CaptureFidelity.SANITIZED, attribution.fidelity)
        assertEquals(CaptureFidelity.OMITTED, privateContact.fidelity)
        assertEquals("PRIVATE_CONTACT_DETAIL", privateContact.reason)
        assertEquals(CaptureFidelity.OMITTED, participantIdentifier.fidelity)
        assertEquals("PRIVATE_IDENTIFIER", participantIdentifier.reason)
    }
}
