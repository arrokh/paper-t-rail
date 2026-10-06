package com.papertrail.api.analysis.execution

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.URI

class ExecutionCaptureSanitizerTest {
    private val sanitizer = ExecutionCaptureSanitizer()

    @Test
    fun `OpenAI request summary omits arbitrary prompt and schema text`() {
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
        assertTrue(result.content.contains("promptContentOmitted"))
        assertTrue(result.content.contains("responseSchemaOmitted"))
        assertFalse(result.content.contains("safe prompt"))
        assertFalse(result.content.contains("json_schema"))
        assertFalse(result.content.contains("claims-private"))
        assertFalse(result.content.contains("private@example.org"))
        assertFalse(result.content.contains("sk-private-value"))
        assertFalse(result.content.contains("P-0042"))
    }

    @Test
    fun `System One request summary omits claim and evidence text`() {
        val body = """
            {"model":"jev-system-one-v1","state":{"claim":"participant P-0042 private@example.org",
             "evidence":"apiKey=sk-private-value","section":"Results"},"questions":{"q1":{"type":"choice"}}}
        """.trimIndent().toByteArray()

        val result = sanitizer.sanitizeSystemOneRequestBody(body)

        assertEquals(CaptureFidelity.PARTIAL, result.fidelity)
        assertEquals("CLAIM_AND_EVIDENCE_TEXT_OMITTED", result.reason)
        assertTrue(result.content!!.contains("jev-system-one-v1"))
        assertTrue(result.content.contains("stateFields"))
        assertTrue(result.content.contains("claimAndEvidenceTextOmitted"))
        assertFalse(result.content.contains("participant"))
        assertFalse(result.content.contains("private@example.org"))
        assertFalse(result.content.contains("P-0042"))
        assertFalse(result.content.contains("sk-private-value"))
        assertFalse(result.content.contains("Results"))
    }

    @Test
    fun `OpenAI response summary omits generated message content`() {
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
        assertTrue(result.content.contains("messageContentOmitted"))
        assertFalse(result.content.contains("private@example.org"))
        assertFalse(result.content.contains("P-0042"))
        assertFalse(result.content.contains("response-private-id"))
        assertFalse(result.content.contains("DIRECT_SUPPORT"))
        assertFalse(result.content.contains("debug"))
    }

    @Test
    fun `Jev response summary retains usage while omitting answer content`() {
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
        assertTrue(result.content.contains("answerCount"))
        assertTrue(result.content.contains("answerContentOmitted"))
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
        assertEquals("UNSUPPORTED_OR_UNSAFE_FIELDS", result.reason)
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
    fun `source PDF and raw parser XML are omitted rather than duplicated`() {
        val request = sanitizer.sanitize("source-document-pdf-request-v1", mapOf("content" to "private paper text"))
        val response = sanitizer.sanitize(
            "source-parser-response-v1",
            mapOf("rawBody" to "<TEI>source document text</TEI>".toByteArray()),
        )

        assertEquals(CaptureFidelity.OMITTED, request.fidelity)
        assertEquals("BINARY_ASSET_REFERENCE", request.reason)
        assertEquals(null, request.content)
        assertEquals(CaptureFidelity.OMITTED, response.fidelity)
        assertEquals("UNSUPPORTED_OR_UNSAFE_FIELDS", response.reason)
        assertEquals(null, response.content)
    }

    @Test
    fun `Crossref request metadata never retains bibliographic search terms or contact email`() {
        val result = sanitizer.sanitizeCrossrefRequest(
            URI("https://api.crossref.org/works?query.bibliographic=Useful%20title%20Ada%20Researcher&rows=10&mailto=private%40example.org"),
        )

        assertEquals(CaptureFidelity.PARTIAL, result.fidelity)
        assertEquals("UNSUPPORTED_FIELDS_OMITTED", result.reason)
        assertTrue(result.content!!.contains("\"rows\":10"))
        assertTrue(result.content.contains("\"bibliographicQueryOmitted\":true"))
        assertTrue(result.content.contains("\"contactParameterOmitted\":true"))
        assertFalse(result.content.contains("Useful title Ada Researcher"))
        assertFalse(result.content.contains("query.bibliographic"))
        assertFalse(result.content.contains("private@example.org"))
        assertFalse(result.content.contains("mailto"))
    }

    @Test
    fun `Unpaywall request keeps the DOI while omitting contact email and unsupported parameters`() {
        val result = sanitizer.sanitizeUnpaywallRequest(
            URI("https://api.unpaywall.org/v2/10.1234/example?email=private%40example.org&tracking=private-value"),
        )

        assertEquals(CaptureFidelity.PARTIAL, result.fidelity)
        assertEquals("UNSUPPORTED_FIELDS_OMITTED", result.reason)
        assertTrue(result.content!!.contains("10.1234/example"))
        assertTrue(result.content.contains("\"contactParameterOmitted\":true"))
        assertTrue(result.content.contains("\"unsupportedParametersOmitted\":true"))
        assertFalse(result.content.contains("private@example.org"))
        assertFalse(result.content.contains("tracking"))
        assertFalse(result.content.contains("private-value"))
    }

    @Test
    fun `System One response keeps typed decisions but omits unknown prose and legends`() {
        val mapper = ObjectMapper()
        val fixture = mapper.readTree(
            javaClass.getResourceAsStream("/provider-fixtures/laya-systemone-responses.json")!!.readBytes(),
        ).path("DIRECT_SUPPORT") as ObjectNode
        val response = fixture.deepCopy()
        response.put("privateNote", "participant P-0042 private@example.org")
        (response.path("answers").path("judgement") as ObjectNode).put("privateExplanation", "Private answer text")

        val result = sanitizer.sanitizeProviderJsonBody("system-one-response-v1", mapper.writeValueAsBytes(response))

        assertEquals(CaptureFidelity.PARTIAL, result.fidelity)
        assertEquals("UNSUPPORTED_FIELDS_OMITTED", result.reason)
        assertTrue(result.content!!.contains("DIRECT_SUPPORT"))
        assertTrue(result.content.contains("input_tokens"))
        assertFalse(result.content.contains("privateNote"))
        assertFalse(result.content.contains("privateExplanation"))
        assertFalse(result.content.contains("Private answer text"))
        assertFalse(result.content.contains("P-0042"))
        assertFalse(result.content.contains("private@example.org"))
        assertFalse(result.content.contains("The passage directly reports evidence"))
    }

    @Test
    fun `System One preflight response retains only bounded token counts and context limit`() {
        val result = sanitizer.sanitizeProviderJsonBody(
            "system-one-preflight-response-v1",
            """{"tokenCounts":[10,11,12],"contextLimit":1024,"debug":"private@example.org"}""".toByteArray(),
        )

        assertEquals(CaptureFidelity.PARTIAL, result.fidelity)
        assertEquals("UNSUPPORTED_FIELDS_OMITTED", result.reason)
        assertTrue(result.content!!.contains("\"contextLimit\":1024"))
        assertTrue(result.content.contains("\"tokenCounts\":[10,11,12]"))
        assertFalse(result.content.contains("debug"))
        assertFalse(result.content.contains("private@example.org"))
    }

    @Test
    fun `open access download request removes user info and query while binary response stays metadata only`() {
        val request = sanitizer.sanitizeOpenAccessRequest(
            URI("https://researcher:secret@repository.example/download/10.1234/example?token=private-value"),
        )
        val metadata = sanitizer.sanitize(
            "open-access-content-metadata-v1",
            mapOf("httpStatus" to 200, "mediaType" to "application/pdf", "byteCount" to 12_345),
        )
        val body = sanitizer.omittedBody("open-access-content-metadata-v1", "BINARY_ASSET_REFERENCE")

        assertEquals(CaptureFidelity.SANITIZED, request.fidelity)
        assertTrue(request.content!!.contains("https://repository.example/download/10.1234/example"))
        assertFalse(request.content.contains("researcher"))
        assertFalse(request.content.contains("secret"))
        assertFalse(request.content.contains("?token="))
        assertFalse(request.content.contains("private-value"))
        assertEquals(CaptureFidelity.SANITIZED, metadata.fidelity)
        assertTrue(metadata.content!!.contains("application/pdf"))
        assertTrue(metadata.content.contains("12345"))
        assertEquals(CaptureFidelity.OMITTED, body.fidelity)
        assertEquals("BINARY_ASSET_REFERENCE", body.reason)
        assertEquals(null, body.content)
    }

    @Test
    fun `Crossref response keeps bounded bibliographic metadata and omits abstracts and arbitrary fields`() {
        val result = sanitizer.sanitizeProviderJsonBody(
            "crossref-response-v1",
            """{"status":"ok","message-type":"work-list","message":{"total-results":1,"query":{"search-terms":"Private source query"},"items":[{"DOI":"10.1234/example","title":["Public study"],"author":[{"given":"Ada","family":"Researcher","ORCID":"https://orcid.org/0000-0000-0000-0000"}],"issued":{"date-parts":[[2024]]},"abstract":"Private abstract text","resource":{"primary":{"URL":"https://publisher.example/paper?token=private-value"}},"apiKey":"sk-private-value"}]}}""".toByteArray(),
        )

        assertEquals(CaptureFidelity.PARTIAL, result.fidelity)
        assertEquals("UNSUPPORTED_FIELDS_OMITTED", result.reason)
        assertTrue(result.content!!.contains("10.1234/example"))
        assertTrue(result.content.contains("Public study"))
        assertTrue(result.content.contains("Ada"))
        assertTrue(result.content.contains("\"publicationYear\":2024"))
        assertFalse(result.content.contains("Private source query"))
        assertFalse(result.content.contains("Private abstract text"))
        assertFalse(result.content.contains("ORCID"))
        assertFalse(result.content.contains("publisher.example"))
        assertFalse(result.content.contains("sk-private-value"))
    }

    @Test
    fun `Unpaywall response keeps safe location metadata but never stores abstract prose`() {
        val result = sanitizer.sanitizeProviderJsonBody(
            "unpaywall-response-v1",
            """{"doi":"10.1234/example","is_oa":true,"oa_status":"green","abstract":"Private abstract prose","title":"Private title text","emailAddress":"private@example.org","oa_locations":[{"url_for_pdf":"https://repository.example/paper.pdf?token=private-value","license":"cc-by","version":"publishedVersion","host_type":"repository","debug":"private response"}]}""".toByteArray(),
        )

        assertEquals(CaptureFidelity.PARTIAL, result.fidelity)
        assertEquals("UNSUPPORTED_FIELDS_OMITTED", result.reason)
        assertTrue(result.content!!.contains("10.1234/example"))
        assertTrue(result.content.contains("\"abstractAvailable\":true"))
        assertTrue(result.content.contains("green"))
        assertTrue(result.content.contains("repository.example/paper.pdf"))
        assertTrue(result.content.contains("cc-by"))
        assertFalse(result.content.contains("Private abstract prose"))
        assertFalse(result.content.contains("Private title text"))
        assertFalse(result.content.contains("private@example.org"))
        assertFalse(result.content.contains("private-value"))
        assertFalse(result.content.contains("debug"))
    }

    @Test
    fun `camel case sensitive fields are removed from provider JSON`() {
        val json = sanitizer.sanitizeProviderJsonBody(
            "crossref-response-v1",
            """{"message":{"DOI":"10.1234/example","title":["Public study"],"emailAddress":"private@example.org","patientName":"private patient name","userName":"private user name","contactEmailOmitted":true}}""".toByteArray(),
        )

        assertEquals(CaptureFidelity.PARTIAL, json.fidelity)
        assertTrue(json.content!!.contains("10.1234/example"))
        assertTrue(json.content.contains("Public study"))
        assertFalse(json.content.contains("emailAddress"))
        assertFalse(json.content.contains("patientName"))
        assertFalse(json.content.contains("userName"))
        assertFalse(json.content.contains("contactEmailOmitted"))
        assertFalse(json.content.contains("private@example.org"))
        assertFalse(json.content.contains("private patient name"))
        assertFalse(json.content.contains("private user name"))
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
