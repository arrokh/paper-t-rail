package com.papertrail.api.openapi

import com.fasterxml.jackson.databind.ObjectMapper
import com.papertrail.api.infrastructure.logging.RequestCorrelationFilter
import com.papertrail.api.citation.parsing.ParsedDocumentView
import com.papertrail.api.citation.parsing.ParsedAtomicClaimView
import com.papertrail.api.citation.parsing.ParsedClaimCitationTargetView
import com.papertrail.api.citation.parsing.ParsedCitationContextView
import com.papertrail.api.citation.parsing.ParsedCitationOccurrenceView
import com.papertrail.api.citation.parsing.ParsedParserProvenance
import com.papertrail.api.scholarly.references.report.ReferenceResolutionReportResponse
import com.papertrail.api.scholarly.references.report.ReferenceResolutionReport
import com.papertrail.api.scholarly.references.report.ReferenceResolutionSummary
import com.papertrail.api.scholarly.references.report.BibliographyResolutionReportEntry
import com.papertrail.api.scholarly.acquisition.report.CitedPaperAccessReport
import com.papertrail.api.scholarly.acquisition.report.CitedReferenceVerificationOutcome
import com.papertrail.api.scholarly.references.service.ReferenceResolutionService
import com.papertrail.api.analysis.http.AnalysisRunPage
import com.papertrail.api.analysis.http.AnalysisRunSummary
import com.papertrail.api.infrastructure.messaging.outbox.OutboxPublisher
import com.papertrail.api.analysis.service.AnalysisRunService
import com.papertrail.api.analysis.configuration.RunConfigurationFactory
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
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.mockito.Mockito
import java.time.Instant
import java.util.UUID

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

    @MockitoBean
    private lateinit var referenceResolutionService: ReferenceResolutionService

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
        val listRuns = analysisRuns.path("get")
        assertTrue(listRuns.path("responses").path("200").path("content").has("application/json"))
        val pageSchemaName = listRuns.path("responses").path("200").path("content").path("application/json").path("schema").path("${'$'}ref").asText().substringAfterLast('/')
        val pageProperties = document.path("components").path("schemas").path(pageSchemaName).path("properties")
        assertTrue(pageProperties.has("items"))
        assertTrue(pageProperties.has("nextCursor"))
        assertTrue(listRuns.path("parameters").any { it.path("name").asText() == "cursor" })
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
        assertTrue(snapshotProperties.has("openAccess"))
        assertTrue(snapshotProperties.has("validationLimits"))
        val parsedDocument = paths.path("/api/v1/analysis-runs/{runId}/parsed-document").path("get")
        assertTrue(parsedDocument.path("responses").path("200").path("content").has("application/json"))
        assertTrue(parsedDocument.path("summary").asText().contains("Atomic Claims"))
        assertTrue(parsedDocument.path("description").asText().contains("inferred/provisional"))
        assertTrue(parsedDocument.path("description").asText().contains("Citation Context"))
        assertTrue(parsedDocument.path("description").asText().contains("PARSED"))
        assertFalse(parsedDocument.path("description").asText().contains("completed Analysis Run"))
        assertTrue(parsedDocument.path("responses").has("404"))
        assertTrue(parsedDocument.path("responses").has("409"))
        val reanalysis = paths.path("/api/v1/documents/{documentId}/analysis-runs").path("post")
        assertTrue(reanalysis.path("requestBody").path("content").has("application/json"))
        val report = paths.path("/api/v1/analysis-runs/{runId}/report").path("get")
        assertTrue(report.path("responses").path("200").path("content").has("application/json"))
        assertTrue(report.path("responses").has("404"))
        val reanalysisSchema = reanalysis.path("requestBody").path("content").path("application/json").path("schema")
        val configSchemaName = reanalysisSchema.path("${'$'}ref").asText().substringAfterLast('/')
        val configProperties = document.path("components").path("schemas").path(configSchemaName).path("properties")
        assertTrue(configProperties.has("claimExtractorProvider"))
        assertTrue(configProperties.has("embeddingProvider"))
        assertTrue(configProperties.has("systemOneProvider"))
        assertTrue(configProperties.has("scholarlyMetadataProvider"))
        assertTrue(configProperties.has("openAccessProvider"))
        assertTrue(configProperties.has("externalProviderConsents"))
        assertTrue(reanalysis.path("responses").has("201"))
        assertTrue(reanalysis.path("responses").has("404"))
        assertTrue(paths.path("/api/v1/health").path("get").path("responses").path("200").path("content").has("application/json"))
    }

    @Test
    fun `analysis run listing returns a page envelope and forwards the cursor`() {
        Mockito.`when`(analysisRunService.list(2, "cursor-token"))
            .thenReturn(AnalysisRunPage(emptyList(), null))

        val response = mockMvc.perform(get("/api/v1/analysis-runs").param("limit", "2").param("cursor", "cursor-token"))
            .andExpect(status().isOk)
            .andReturn()
            .response
        val page = objectMapper.readTree(response.contentAsString)

        assertTrue(page.path("items").isArray)
        assertEquals(0, page.path("items").size())
        assertTrue(page.path("nextCursor").isNull)
        Mockito.verify(analysisRunService).list(2, "cursor-token")
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
        val scholarlyMetadataOptions = providers.path("scholarlyMetadata")
        val openAccessOptions = providers.path("openAccess")
        assertEquals(setOf("claimExtractor", "embedding", "systemOne", "scholarlyMetadata", "openAccess"), providers.fieldNames().asSequence().toSet())
        assertEquals(1, claimExtractorOptions.size())
        assertEquals(1, embeddingOptions.size())
        assertEquals(1, systemOneOptions.size())
        assertEquals(1, scholarlyMetadataOptions.size())
        assertEquals(1, openAccessOptions.size())
        val providerOptions = listOf(claimExtractorOptions, embeddingOptions, systemOneOptions, scholarlyMetadataOptions, openAccessOptions).flatMap { it.toList() }
        assertEquals(5, providerOptions.size)
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
            "cited_paper_location",
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

        assertTrue(runCatching { UUID.fromString(invalidId) }.isSuccess)
        assertTrue(runCatching { UUID.fromString(missingId) }.isSuccess)
        assertTrue(invalidId != missingId)
    }

    @Test
    fun `parsed document endpoint returns the stored structure and distinguishes pending or missing runs`() {
        val runId = UUID.randomUUID()
        val documentId = UUID.randomUUID()
        val summary = AnalysisRunSummary(
            id = runId,
            documentId = documentId,
            filename = "paper.pdf",
            sourceContentSha256 = "a".repeat(64),
            status = "PARSED",
            progress = objectMapper.readTree("""{"stage":"PARSED"}"""),
            configuration = objectMapper.readTree("{}"),
            createdAt = Instant.now(),
            startedAt = null,
            failureReason = null,
        )
        val parsed = ParsedDocumentView(
            parser = ParsedParserProvenance("grobid", "0.9.1-crf"),
            sourceContentSha256 = summary.sourceContentSha256,
            normalizedSourceText = "Claim [1].",
            sections = emptyList(),
            citationContexts = listOf(
                ParsedCitationContextView(
                    id = UUID.randomUUID(),
                    sectionId = UUID.randomUUID(),
                    boundaryKind = "SENTENCE_FALLBACK",
                    text = "Claim [1].",
                    startOffset = 0,
                    endOffset = 10,
                    occurrences = listOf(
                        ParsedCitationOccurrenceView(
                            UUID.randomUUID(), "[1]", 6, 9, listOf("ref1"),
                        ),
                    ),
                    atomicClaims = listOf(
                        ParsedAtomicClaimView(
                            id = UUID.randomUUID(),
                            text = "Claim",
                            sourceStartOffset = 0,
                            sourceEndOffset = 5,
                            citationTargets = listOf(
                                ParsedClaimCitationTargetView(
                                    id = UUID.randomUUID(),
                                    markerText = "[1]",
                                    bibliographyReferenceKey = "ref1",
                                    bibliographyTitle = "Reference one",
                                    associationKind = "INFERRED_PROVISIONAL",
                                ),
                            ),
                        ),
                    ),
                ),
            ),
            bibliographyEntries = emptyList(),
        )
        Mockito.`when`(analysisRunService.getParsedDocument(runId)).thenReturn(parsed)

        mockMvc.perform(get("/api/v1/analysis-runs/$runId/parsed-document"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.parser.provider").value("grobid"))
            .andExpect(jsonPath("$.normalizedSourceText").value("Claim [1]."))
            .andExpect(jsonPath("$.citationContexts[0].occurrences[0].bibliographyReferenceKeys[0]").value("ref1"))
            .andExpect(jsonPath("$.citationContexts[0].atomicClaims[0].text").value("Claim"))
            .andExpect(jsonPath("$.citationContexts[0].atomicClaims[0].sourceStartOffset").value(0))
            .andExpect(jsonPath("$.citationContexts[0].atomicClaims[0].citationTargets[0].associationKind").value("INFERRED_PROVISIONAL"))

        val pendingRunId = UUID.randomUUID()
        Mockito.`when`(analysisRunService.getParsedDocument(pendingRunId)).thenThrow(
            ResponseStatusException(HttpStatus.CONFLICT, "Parsed document structure is not ready for this Analysis Run."),
        )
        mockMvc.perform(get("/api/v1/analysis-runs/$pendingRunId/parsed-document"))
            .andExpect(status().isConflict)

        val missingRunId = UUID.randomUUID()
        Mockito.`when`(analysisRunService.getParsedDocument(missingRunId)).thenThrow(
            ResponseStatusException(HttpStatus.NOT_FOUND, "Analysis Run not found."),
        )
        mockMvc.perform(get("/api/v1/analysis-runs/$missingRunId/parsed-document"))
            .andExpect(status().isNotFound)
    }

    @Test
    fun `report endpoint exposes persisted reference outcomes and pinned policy`() {
        val runId = UUID.randomUUID()
        Mockito.`when`(referenceResolutionService.report(runId)).thenReturn(
            ReferenceResolutionReportResponse(
                analysisRunId = runId,
                runStatus = "PARSED",
                referenceResolution = ReferenceResolutionReport(
                    executionStatus = "COMPLETED",
                    scorePolicyVersion = "title-author-year-weighted-edit-similarity-v1",
                    confidenceThreshold = 0.9,
                    summary = ReferenceResolutionSummary(2, 1, 0, 1, 0, 0),
                    entries = listOf(
                        BibliographyResolutionReportEntry(
                            entryOrder = 1,
                            localReferenceKey = "ref1",
                            rawText = "Example paper, 2024",
                            title = "Example paper",
                            authors = listOf("Riley Example"),
                            year = 2024,
                            doi = "10.1234/example",
                            referenceType = "JOURNAL_ARTICLE",
                            status = "RESOLVED",
                            reasonCode = null,
                            canonicalPaper = null,
                            confidenceScore = 1.0,
                            matchMethod = "DOI",
                            citedPaperAccess = CitedPaperAccessReport(
                                accessStatus = "ABSTRACT_ONLY",
                                accessReason = "ABSTRACT_ONLY",
                                providerId = "recorded-fixtures",
                                sourceUrl = null,
                                license = null,
                                version = null,
                                hostType = null,
                                discoveredAt = Instant.parse("2025-01-01T00:00:00Z"),
                                contentSha256 = null,
                                language = null,
                                languageDetectorVersion = null,
                                verificationOutcomes = listOf(
                                    CitedReferenceVerificationOutcome(UUID.randomUUID(), "INSUFFICIENT_EVIDENCE", "ABSTRACT_ONLY", "ABSTRACT_ONLY"),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )

        mockMvc.perform(get("/api/v1/analysis-runs/$runId/report"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.referenceResolution.executionStatus").value("COMPLETED"))
            .andExpect(jsonPath("$.referenceResolution.scorePolicyVersion").value("title-author-year-weighted-edit-similarity-v1"))
            .andExpect(jsonPath("$.referenceResolution.confidenceThreshold").value(0.9))
            .andExpect(jsonPath("$.referenceResolution.summary.unsupportedReferenceType").value(1))
            .andExpect(jsonPath("$.referenceResolution.summary.failed").value(0))
            .andExpect(jsonPath("$.referenceResolution.entries[0].citedPaperAccess.accessStatus").value("ABSTRACT_ONLY"))
            .andExpect(jsonPath("$.referenceResolution.entries[0].citedPaperAccess.accessReason").value("ABSTRACT_ONLY"))
            .andExpect(jsonPath("$.referenceResolution.entries[0].citedPaperAccess.verificationOutcomes[0].finalStatus").value("INSUFFICIENT_EVIDENCE"))
    }

    @Test
    fun `Swagger UI is available from the API`() {
        mockMvc.perform(get("/swagger-ui/index.html"))
            .andExpect(status().isOk)
            .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
    }
}
