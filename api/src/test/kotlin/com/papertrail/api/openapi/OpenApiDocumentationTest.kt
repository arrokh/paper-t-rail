package com.papertrail.api.openapi

import com.fasterxml.jackson.databind.ObjectMapper
import com.papertrail.api.logging.RequestCorrelationFilter
import com.papertrail.api.queue.OutboxPublisher
import com.papertrail.api.runs.AnalysisRunService
import com.papertrail.api.runs.RunConfigurationFactory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.bean.override.mockito.MockitoBean
import jakarta.servlet.FilterChain
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@SpringBootTest(properties = ["paper-trail.role=api"])
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension::class)
class OpenApiDocumentationTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var requestCorrelationFilter: RequestCorrelationFilter

    @MockitoBean
    private lateinit var analysisRunService: AnalysisRunService

    @MockitoBean
    private lateinit var configurationFactory: RunConfigurationFactory

    @MockitoBean
    private lateinit var jdbc: JdbcTemplate

    @MockitoBean
    private lateinit var outboxPublisher: OutboxPublisher

    @Test
    fun `OpenAPI contract describes the existing analysis run endpoints and PDF upload`() {
        val response = mockMvc.perform(get("/v3/api-docs"))
            .andExpect(status().isOk)
            .andReturn()
            .response
            .contentAsString
        val document = objectMapper.readTree(response)
        assertEquals("Paper T-Rail API", document.path("info").path("title").asText())
        assertEquals("0.1.0", document.path("info").path("version").asText())
        val paths = document.path("paths")

        assertTrue(paths.has("/api/v1/providers"))
        val providerListing = paths.path("/api/v1/providers").path("get")
        assertTrue(providerListing.path("responses").path("200").path("content").has("application/json"))
        assertTrue(paths.has("/api/v1/analysis-runs"))
        val analysisRuns = paths.path("/api/v1/analysis-runs")
        assertTrue(analysisRuns.path("get").path("responses").path("200").path("content").has("application/json"))
        val upload = analysisRuns.path("post")
        assertEquals("Upload a PDF and create an Analysis Run", upload.path("summary").asText())
        assertTrue(upload.path("responses").path("201").path("content").has("application/json"))
        assertFalse(upload.path("parameters").any { it.path("name").asText() == "configuration" })
        assertTrue(upload.path("requestBody").path("content").has("multipart/form-data"))
        val uploadSchema = upload.path("requestBody").path("content").path("multipart/form-data").path("schema")
        val uploadProperties = if (uploadSchema.has("${'$'}ref")) {
            val schemaName = uploadSchema.path("${'$'}ref").asText().substringAfterLast('/')
            document.path("components").path("schemas").path(schemaName).path("properties")
        } else {
            uploadSchema.path("properties")
        }
        assertTrue(uploadProperties.has("configuration"))
        assertEquals("string", uploadProperties.path("file").path("type").asText())
        assertEquals("binary", uploadProperties.path("file").path("format").asText())

        val getRun = paths.path("/api/v1/analysis-runs/{runId}").path("get")
        assertTrue(getRun.path("responses").path("200").path("content").has("application/json"))
        assertTrue(getRun.path("responses").has("404"))
        val runSchemaName = getRun.path("responses").path("200").path("content").path("application/json").path("schema").path("${'$'}ref").asText().substringAfterLast('/')
        val runProperties = document.path("components").path("schemas").path(runSchemaName).path("properties")
        val progressSchemaName = runProperties.path("progress").path("${'$'}ref").asText().substringAfterLast('/')
        val progressProperties = document.path("components").path("schemas").path(progressSchemaName).path("properties")
        assertTrue(progressProperties.has("stage"))
        assertTrue(progressProperties.has("message"))
        val snapshotSchemaName = runProperties.path("configuration").path("${'$'}ref").asText().substringAfterLast('/')
        val snapshotProperties = document.path("components").path("schemas").path(snapshotSchemaName).path("properties")
        assertTrue(snapshotProperties.has("claimExtractor"))
        assertTrue(snapshotProperties.has("validationLimits"))
        val reanalysis = paths.path("/api/v1/documents/{documentId}/analysis-runs").path("post")
        assertTrue(reanalysis.path("requestBody").path("content").has("application/json"))
        val reanalysisSchema = reanalysis.path("requestBody").path("content").path("application/json").path("schema")
        val configSchemaName = reanalysisSchema.path("${'$'}ref").asText().substringAfterLast('/')
        val configProperties = document.path("components").path("schemas").path(configSchemaName).path("properties")
        assertTrue(configProperties.has("claimExtractorProvider"))
        assertTrue(configProperties.has("embeddingProvider"))
        assertTrue(configProperties.has("systemOneProvider"))
        assertTrue(configProperties.has("externalProviderConsents"))
        assertTrue(reanalysis.path("responses").has("201"))
        assertTrue(reanalysis.path("responses").has("404"))
        assertTrue(paths.path("/api/v1/health").path("get").path("responses").path("200").path("content").has("application/json"))
    }

    @Test
    fun `provider directory exposes only enabled local choices and stable category descriptions`() {
        val response = mockMvc.perform(get("/api/v1/providers"))
            .andExpect(status().isOk)
            .andReturn()
            .response
            .contentAsString
        val directory = objectMapper.readTree(response)
        val providers = directory.path("providers")
        val claimExtractorOptions = providers.path("claimExtractor")
        val embeddingOptions = providers.path("embedding")
        val systemOneOptions = providers.path("systemOne")
        assertEquals(setOf("claimExtractor", "embedding", "systemOne"), providers.fieldNames().asSequence().toSet())
        assertEquals(1, claimExtractorOptions.size())
        assertEquals(1, embeddingOptions.size())
        assertEquals(1, systemOneOptions.size())
        val providerOptions = listOf(claimExtractorOptions, embeddingOptions, systemOneOptions).flatMap { it.toList() }
        assertEquals(3, providerOptions.size)
        assertTrue(providerOptions.all { it.path("trustBoundary").asText() == "LOCAL" })
        assertFalse(providerOptions.any { it.path("providerId").asText() in setOf("jev", "google-gemini-api", "unreviewed-provider") })
        val disclosedCategoryIds = directory.path("dataCategories").map { it.path("id").asText() }.toSet()
        assertTrue(disclosedCategoryIds.containsAll(setOf(
            "source_document_text",
            "bibliographic_metadata",
            "citation_context",
            "cited_paper_chunks",
            "atomic_claims",
            "evidence_passages",
            "embedding_input",
        )))
    }

    @Test
    fun `API returns the request ID and writes it with response metadata as structured JSON`(capturedOutput: CapturedOutput) {
        val response = mockMvc.perform(get("/api/v1/health?privateQueryMarker=do-not-log").header("X-Request-ID", "trace-known-42"))
            .andExpect(status().isOk)
            .andReturn()
            .response

        assertEquals("trace-known-42", response.getHeader("X-Request-ID"))
        val logRecord = capturedOutput.all.lineSequence()
            .filter { it.startsWith("{") }
            .map(objectMapper::readTree)
            .firstOrNull { it.path("message").asText() == "HTTP request completed" }
        assertTrue(logRecord != null, "Expected an ECS JSON HTTP completion log")
        assertEquals("trace-known-42", logRecord!!.path("requestId").asText())
        assertEquals("paper-t-rail-api", logRecord.path("service").path("name").asText())
        assertTrue(logRecord.has("@timestamp"))
        assertEquals("INFO", logRecord.path("log").path("level").asText())
        assertEquals("GET", logRecord.path("httpMethod").asText())
        assertEquals(200, logRecord.path("httpStatus").asInt())
        assertFalse(capturedOutput.all.contains("privateQueryMarker=do-not-log"))
    }

    @Test
    fun `API logs uncaught request failures with a server status and safe error type`(capturedOutput: CapturedOutput) {
        val request = MockHttpServletRequest("GET", "/api/v1/private")
        request.addHeader("X-Request-ID", "trace-failed-50")
        val response = MockHttpServletResponse()

        assertThrows(IllegalStateException::class.java) {
            requestCorrelationFilter.doFilter(
                request,
                response,
                FilterChain { _, _ -> throw IllegalStateException("private exception details") },
            )
        }

        assertEquals("trace-failed-50", response.getHeader("X-Request-ID"))
        val failureLog = capturedOutput.all.lineSequence()
            .filter { it.startsWith("{") }
            .map(objectMapper::readTree)
            .firstOrNull { it.path("message").asText() == "HTTP request failed" }
        assertTrue(failureLog != null, "Expected an ECS JSON HTTP failure log")
        assertEquals("trace-failed-50", failureLog!!.path("requestId").asText())
        assertEquals(500, failureLog.path("httpStatus").asInt())
        assertEquals("IllegalStateException", failureLog.path("errorType").asText())
        assertFalse(capturedOutput.all.contains("private exception details"))
    }

    @Test
    fun `API replaces invalid and missing request IDs with independent UUIDs`() {
        val invalidId = mockMvc.perform(get("/api/v1/health").header("X-Request-ID", "bad id with spaces"))
            .andExpect(status().isOk)
            .andReturn()
            .response
            .getHeader("X-Request-ID")
        val missingId = mockMvc.perform(get("/api/v1/health"))
            .andExpect(status().isOk)
            .andReturn()
            .response
            .getHeader("X-Request-ID")

        assertTrue(runCatching { java.util.UUID.fromString(invalidId) }.isSuccess)
        assertTrue(runCatching { java.util.UUID.fromString(missingId) }.isSuccess)
        assertTrue(invalidId != missingId)
    }

    @Test
    fun `Swagger UI is available from the API`() {
        mockMvc.perform(get("/swagger-ui/index.html"))
            .andExpect(status().isOk)
            .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
    }
}
