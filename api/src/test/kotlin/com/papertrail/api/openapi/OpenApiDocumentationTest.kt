package com.papertrail.api.openapi

import com.fasterxml.jackson.databind.ObjectMapper
import com.papertrail.api.infrastructure.cache.OperatorCredentialVerifier
import com.papertrail.api.infrastructure.logging.RequestCorrelationFilter
import com.papertrail.api.analysis.http.ParsedDocumentView
import com.papertrail.api.citation.parsing.BibliographyNormalizationPolicySelection
import com.papertrail.api.citation.parsing.ParsedBibliographyIdentifier
import com.papertrail.api.citation.parsing.ParsedBibliographySourceLocation
import com.papertrail.api.analysis.http.ParsedAtomicClaimView
import com.papertrail.api.analysis.http.ParsedClaimCitationTargetView
import com.papertrail.api.analysis.http.ParsedCitationContextView
import com.papertrail.api.analysis.http.ParsedCitationOccurrenceView
import com.papertrail.api.analysis.http.ParsedParserProvenance
import com.papertrail.api.analysis.report.http.ReferenceResolutionReportResponse
import com.papertrail.api.analysis.report.service.AnalysisRunReportService
import com.papertrail.api.scholarly.references.report.ReferenceResolutionReport
import com.papertrail.api.scholarly.references.report.ReferenceResolutionSummary
import com.papertrail.api.scholarly.references.report.BibliographyResolutionReportEntry
import com.papertrail.api.scholarly.references.report.ReportCanonicalPaper
import com.papertrail.api.scholarly.references.resolver.ScholarlyCandidateEvidence
import com.papertrail.api.scholarly.acquisition.report.CitedPaperAccessReport
import com.papertrail.api.external.unpaywall.UnpaywallDiscoveryCache
import com.papertrail.api.external.unpaywall.http.UnpaywallCacheInvalidationRequest
import com.papertrail.api.external.unpaywall.service.UnpaywallCacheInvalidationService
import com.papertrail.api.evidence.report.CitedReferenceVerificationOutcome
import com.papertrail.api.review.domain.HumanReview
import com.papertrail.api.review.domain.HumanReviewAction
import com.papertrail.api.review.http.CreateHumanReviewRequest
import com.papertrail.api.review.service.HumanReviewService
import com.papertrail.api.scholarly.acquisition.domain.TerminalVerificationStatus
import com.papertrail.api.evidence.report.EvidenceCoverageReport
import com.papertrail.api.evidence.report.EvidenceCoverageSummary
import com.papertrail.api.scholarly.references.service.ReferenceResolutionService
import com.papertrail.api.external.crossref.CrossrefLookupCache
import com.papertrail.api.external.crossref.http.CrossrefCacheInvalidationRequest
import com.papertrail.api.external.crossref.http.CrossrefCacheLookupType
import com.papertrail.api.external.crossref.service.CrossrefCacheInvalidationService
import com.papertrail.api.analysis.http.AnalysisRunPage
import com.papertrail.api.analysis.http.AnalysisRunSourcePdfAccess
import com.papertrail.api.analysis.http.AnalysisRunSummary
import com.papertrail.api.analysis.execution.service.AnalysisRunExecutionService
import com.papertrail.api.analysis.execution.http.AnalysisRunExecutionSummary
import com.papertrail.api.analysis.execution.http.ExecutionArtifactResponse
import com.papertrail.api.infrastructure.messaging.outbox.OutboxPublisher
import com.papertrail.api.analysis.service.AnalysisRunService
import com.papertrail.api.document.service.SourceDocumentDeletionService
import com.papertrail.api.analysis.recovery.domain.RecoveryRightsDeclaration
import com.papertrail.api.analysis.recovery.domain.RecoveryAssetSelectionMethod
import com.papertrail.api.analysis.recovery.domain.RecoveryIdentityConfirmation
import com.papertrail.api.analysis.recovery.domain.RecoveryIdentityOutcome
import com.papertrail.api.analysis.recovery.domain.RecoveryLanguageEligibility
import com.papertrail.api.analysis.recovery.domain.RecoveryUploadAssetSelection
import com.papertrail.api.analysis.recovery.domain.RecoveryUploadValidationAttempt
import com.papertrail.api.analysis.recovery.domain.RecoveryValidationStatus
import com.papertrail.api.analysis.recovery.http.RecoveryUploadValidationResponse
import com.papertrail.api.analysis.recovery.service.RecoveryStagingService
import com.papertrail.api.analysis.recovery.service.RecoveryUploadIdentityService
import com.papertrail.api.analysis.recovery.service.RecoveryUploadValidationService
import com.papertrail.api.analysis.recovery.service.RecoveryStagingSettings
import com.papertrail.api.analysis.recovery.service.RecoveryUploadLimits
import com.papertrail.api.analysis.configuration.RunConfigurationFactory
import com.papertrail.api.citation.parsing.ParsedBibliographicMetadataCandidate
import com.papertrail.api.citation.parsing.ParsedBibliographicMetadataExtractionMethod
import com.papertrail.api.citation.parsing.ParsedBibliographicMetadataField
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.mockito.Mockito
import java.time.Instant
import java.util.UUID

@SpringBootTest(properties = ["paper-trail.role=api", "paper-trail.operator.credential=operator-test-credential"])
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension::class)
class OpenApiDocumentationTest {
    @Value("\${paper-trail.analysis.system-one-aggregation.enabled}")
    private var systemOneAggregationEnabled: Boolean = false

    @Value("\${paper-trail.upload.max-bytes}")
    private var analysisRunMaxUploadBytes: Long = 0

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var recoveryStagingSettings: RecoveryStagingSettings

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var requestCorrelationFilter: RequestCorrelationFilter

    @MockitoBean
    private lateinit var analysisRunService: AnalysisRunService

    @MockitoBean
    private lateinit var analysisRunExecutionService: AnalysisRunExecutionService

    @MockitoBean
    private lateinit var sourceDocumentDeletionService: SourceDocumentDeletionService

    @MockitoBean
    private lateinit var recoveryStagingService: RecoveryStagingService

    @MockitoBean
    private lateinit var recoveryUploadValidationService: RecoveryUploadValidationService

    @MockitoBean
    private lateinit var recoveryUploadIdentityService: RecoveryUploadIdentityService

    @MockitoBean
    private lateinit var configurationFactory: RunConfigurationFactory

    @MockitoBean
    private lateinit var jdbc: JdbcTemplate

    @MockitoBean
    private lateinit var outboxPublisher: OutboxPublisher

    @MockitoBean
    private lateinit var referenceResolutionService: ReferenceResolutionService

    @MockitoBean
    private lateinit var analysisRunReportService: AnalysisRunReportService

    @MockitoBean
    private lateinit var humanReviewService: HumanReviewService

    @MockitoBean
    private lateinit var crossrefLookupCache: CrossrefLookupCache

    @MockitoBean
    private lateinit var unpaywallDiscoveryCache: UnpaywallDiscoveryCache

    @Test
    fun `legacy execution summary returns an explicit unavailable state without invented timing`() {
        val runId = UUID.randomUUID()
        Mockito.`when`(analysisRunExecutionService.summary(runId)).thenReturn(
            AnalysisRunExecutionSummary(runId, null, false, "NOT_RECORDED", "NOT_RECORDED", null, null, null),
        )

        val response = mockMvc.perform(get("/api/v1/analysis-runs/$runId/execution"))
            .andExpect(status().isOk)
            .andReturn()
            .response
        val body = objectMapper.readTree(response.contentAsString)

        assertEquals(runId.toString(), body.path("analysisRunId").asText())
        assertTrue(body.path("captureRequested").isNull || body.path("captureRequested").isMissingNode)
        assertEquals(false, body.path("captureEnabled").asBoolean())
        assertEquals("NOT_RECORDED", body.path("recordingState").asText())
        assertTrue(body.path("startedAt").isNull)
        assertTrue(body.path("finishedAt").isNull)
        assertTrue(body.path("totalDurationMillis").isNull)
        assertTrue(body.path("gapReason").isNull)
    }

    @Test
    fun `execution summary returns the safe reason for an incomplete trace`() {
        val runId = UUID.randomUUID()
        Mockito.`when`(analysisRunExecutionService.summary(runId)).thenReturn(
            AnalysisRunExecutionSummary(
                runId, true, true, "STOPPED", "INCOMPLETE", Instant.parse("2026-10-01T00:00:00Z"),
                Instant.parse("2026-10-01T00:00:12Z"), 12_000, "UNSAFE_SPAN_METADATA_OMITTED",
            ),
        )

        val response = mockMvc.perform(get("/api/v1/analysis-runs/$runId/execution"))
            .andExpect(status().isOk)
            .andReturn()
            .response

        assertEquals("UNSAFE_SPAN_METADATA_OMITTED", objectMapper.readTree(response.contentAsString).path("gapReason").asText())
    }

    @Test
    fun `execution artifact response is no-store and carries only sanitized content`() {
        val runId = UUID.randomUUID()
        val artifactId = UUID.randomUUID()
        val spanId = UUID.randomUUID()
        Mockito.`when`(analysisRunExecutionService.artifact(runId, artifactId, spanId, "RESULT")).thenReturn(
            ExecutionArtifactResponse(
                id = artifactId,
                spanId = spanId,
                role = "RESULT",
                fidelity = "SANITIZED",
                reason = null,
                mediaType = "application/json",
                content = """{"status":"SUCCEEDED"}""",
                schemaVersion = "run-stage-result-v1",
                captureVersion = "execution-capture-v1",
                sanitizerVersion = "structured-redaction-sanitizer-v3",
                sizeBytes = 22,
            ),
        )

        val response = mockMvc.perform(
            get("/api/v1/analysis-runs/$runId/execution/artifacts/$artifactId")
                .param("spanId", spanId.toString())
                .param("role", "RESULT"),
        )
            .andExpect(status().isOk)
            .andReturn()
            .response

        assertEquals("no-store", response.getHeader("Cache-Control"))
        assertEquals("SANITIZED", objectMapper.readTree(response.contentAsString).path("fidelity").asText())
        assertEquals("""{"status":"SUCCEEDED"}""", objectMapper.readTree(response.contentAsString).path("content").asText())
        Mockito.verify(analysisRunExecutionService).artifact(runId, artifactId, spanId, "RESULT")
    }

    @Test
    fun `base Spring configuration enables experimental System One aggregation by default`() {
        assertTrue(systemOneAggregationEnabled)
    }

    @Test
    fun `source PDF access returns short-lived no-store links from its Analysis Run`() {
        val runId = UUID.randomUUID()
        val expiresAt = Instant.parse("2026-10-01T00:00:00Z")
        Mockito.`when`(analysisRunService.getSourcePdfAccess(runId))
            .thenReturn(AnalysisRunSourcePdfAccess("source paper.pdf", "http://127.0.0.1:9000/view", "http://127.0.0.1:9000/download", expiresAt))

        val response = mockMvc.perform(get("/api/v1/analysis-runs/$runId/source-document"))
            .andExpect(status().isOk)
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andReturn()
            .response

        val body = objectMapper.readTree(response.contentAsString)
        assertEquals("source paper.pdf", body.path("filename").asText())
        assertEquals("http://127.0.0.1:9000/view", body.path("viewUrl").asText())
        assertEquals("http://127.0.0.1:9000/download", body.path("downloadUrl").asText())
        assertEquals(expiresAt, Instant.parse(body.path("expiresAt").asText()))
        assertEquals("no-store", response.getHeader("Cache-Control"))
    }

    @Test
    fun `recovery per-file limit inherits the Analysis Run PDF upload limit`() {
        assertEquals(analysisRunMaxUploadBytes, recoveryStagingSettings.maxFileBytes)
        assertEquals(10, recoveryStagingSettings.maxFilesPerBatch)
        assertEquals(262_144_000, recoveryStagingSettings.maxBatchBytes)
        assertEquals(300, recoveryStagingSettings.uploadUrlTtlSeconds)
    }

    @Test
    fun `rights declaration endpoint returns exact text and configured upload limits without caching`() {
        Mockito.`when`(recoveryStagingService.currentRightsDeclaration()).thenReturn(RecoveryRightsDeclaration.CURRENT)
        Mockito.`when`(recoveryStagingService.currentLimits()).thenReturn(
            RecoveryUploadLimits(50_000_000, 10, 250_000_000, 300, 604_800),
        )

        mockMvc.perform(get("/api/v1/recovery-rights-declaration"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.version").value("recovery-rights-v1"))
            .andExpect(jsonPath("$.text").value(RecoveryRightsDeclaration.CURRENT.text))
            .andExpect(jsonPath("$.maxFileBytes").value(50_000_000))
            .andExpect(jsonPath("$.maxFilesPerBatch").value(10))
            .andExpect(jsonPath("$.maxBatchBytes").value(250_000_000))
            .andExpect(jsonPath("$.uploadUrlTtlSeconds").value(300))
            .andExpect(jsonPath("$.inactivityTtlSeconds").value(604_800))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Cache-Control", "no-store"))
    }

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
        assertTrue(providerListing.path("description").asText().contains("opaque fingerprint"))
        val providerDirectorySchema = providerListing.path("responses").path("200").path("content")
            .path("application/json").path("schema").path("${'$'}ref").asText().substringAfterLast('/')
        val directoryProperties = document.path("components").path("schemas").path(providerDirectorySchema).path("properties")
        val providerOptionsSchema = directoryProperties.path("providers").path("additionalProperties").path("items")
            .path("${'$'}ref").asText().substringAfterLast('/')
        val providerOptionProperties = document.path("components").path("schemas").path(providerOptionsSchema).path("properties")
        assertTrue(providerOptionProperties.has("retentionDisclosure"))
        assertTrue(providerOptionProperties.has("retentionDisclosureFingerprint"))
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
        assertTrue(upload.path("description").asText().contains("claim-citation pair limit"))
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
        assertTrue(uploadProperties.path("configuration").path("description").asText().contains("retentionDisclosureFingerprint"))
        assertTrue(uploadProperties.path("configuration").path("description").asText().contains("captureExecution=false"))
        assertTrue(uploadProperties.path("configuration").path("description").asText().contains("trusted local workspace"))
        assertEquals("string", uploadProperties.path("file").path("type").asText())
        assertEquals("binary", uploadProperties.path("file").path("format").asText())

        val rightsDeclaration = paths.path("/api/v1/recovery-rights-declaration").path("get")
        assertTrue(rightsDeclaration.path("responses").has("200"))
        val rightsSchemaName = rightsDeclaration.path("responses").path("200").path("content").path("application/json").path("schema").path("${'$'}ref").asText().substringAfterLast('/')
        val rightsProperties = document.path("components").path("schemas").path(rightsSchemaName).path("properties")
        listOf("version", "text", "maxFileBytes", "maxFilesPerBatch", "maxBatchBytes", "uploadUrlTtlSeconds", "inactivityTtlSeconds").forEach { assertTrue(rightsProperties.has(it)) }

        val recoveryBatchesPath = "/api/v1/analysis-runs/{analysisRunId}/recovery-batches"
        val listRecoveryBatches = paths.path(recoveryBatchesPath).path("get")
        assertTrue(listRecoveryBatches.path("responses").has("200"))
        assertTrue(listRecoveryBatches.path("responses").has("404"))
        assertTrue(listRecoveryBatches.path("responses").path("200").path("content").path("application/json").path("schema").path("items").has("${'$'}ref"))
        val createRecoveryBatch = paths.path(recoveryBatchesPath).path("post")
        assertEquals("Create or retrieve a Recovery Batch", createRecoveryBatch.path("summary").asText())
        assertTrue(createRecoveryBatch.path("requestBody").path("required").asBoolean())
        assertTrue(createRecoveryBatch.path("responses").has("400"))
        assertTrue(createRecoveryBatch.path("responses").has("410"))
        val createBatchSchemaName = createRecoveryBatch.path("requestBody").path("content").path("application/json").path("schema").path("${'$'}ref").asText().substringAfterLast('/')
        val createBatchProperties = document.path("components").path("schemas").path(createBatchSchemaName).path("properties")
        assertTrue(createBatchProperties.has("idempotencyKey"))
        assertTrue(createBatchProperties.has("rightsDeclarationAccepted"))
        assertTrue(createBatchProperties.has("rightsDeclarationVersion"))
        val createUpload = paths.path("/api/v1/recovery-batches/{batchId}/entries/{localReferenceKey}/uploads").path("post")
        assertTrue(createUpload.path("responses").has("409"))
        assertTrue(createUpload.path("responses").has("413"))
        assertTrue(createUpload.path("responses").has("503"))
        val uploadIntentSchemaName = createUpload.path("responses").path("200").path("content").path("application/json").path("schema").path("${'$'}ref").asText().substringAfterLast('/')
        val uploadIntentProperties = document.path("components").path("schemas").path(uploadIntentSchemaName).path("properties")
        assertTrue(uploadIntentProperties.has("uploadUrl"))
        assertTrue(uploadIntentProperties.has("requiredHeaders"))
        val recoveryUploadSchemaName = uploadIntentProperties.path("upload").path("${'$'}ref").asText().substringAfterLast('/')
        val recoveryUploadProperties = document.path("components").path("schemas").path(recoveryUploadSchemaName).path("properties")
        assertTrue(recoveryUploadProperties.has("idempotencyKey"))
        assertTrue(uploadIntentProperties.path("uploadUrl").path("description").asText().contains("bearer"))
        val finalizeUpload = paths.path("/api/v1/recovery-batches/{batchId}/uploads/{uploadId}/finalize").path("post")
        assertTrue(finalizeUpload.path("responses").has("409"))
        assertTrue(finalizeUpload.path("responses").path("409").path("description").asText().contains("bytes are missing"))
        assertTrue(finalizeUpload.path("responses").has("422"))
        assertTrue(finalizeUpload.path("responses").path("422").path("description").asText().contains("byte size or SHA-256"))
        assertTrue(finalizeUpload.path("responses").path("422").path("description").asText().contains("selectable text"))
        assertTrue(finalizeUpload.path("responses").path("422").path("description").asText().contains("scanned PDFs are unsupported"))
        assertTrue(finalizeUpload.path("responses").path("422").path("description").asText().contains("encrypted"))
        assertTrue(finalizeUpload.path("responses").path("422").path("description").asText().contains("incomplete"))
        val validateUpload = paths.path("/api/v1/recovery-batches/{batchId}/uploads/{uploadId}/validate").path("post")
        assertTrue(validateUpload.path("responses").has("200"))
        assertTrue(validateUpload.path("responses").has("404"))
        assertTrue(validateUpload.path("responses").has("409"))
        assertTrue(validateUpload.path("description").asText().contains("does not confirm identity"))
        assertTrue(validateUpload.path("description").asText().contains("start evidence assessment"))
        val validationSchemaName = validateUpload.path("responses").path("200").path("content").path("application/json").path("schema").path("${'$'}ref").asText().substringAfterLast('/')
        val validationProperties = document.path("components").path("schemas").path(validationSchemaName).path("properties")
        listOf("parserVersion", "metadataExtractionPolicyVersion", "identityPolicyVersion", "parserOptions", "metadataCandidates", "identityOutcome", "humanConfirmation", "selection", "languageEligibility", "failureCode").forEach { assertTrue(validationProperties.has(it)) }
        val confirmIdentity = paths.path("/api/v1/recovery-batches/{batchId}/uploads/{uploadId}/validation/{attemptId}/confirm-identity").path("post")
        assertTrue(confirmIdentity.path("responses").has("200"))
        assertTrue(confirmIdentity.path("responses").has("400"))
        assertTrue(confirmIdentity.path("responses").has("409"))
        assertTrue(confirmIdentity.path("description").asText().contains("mismatches, unsupported chapter identities, and inconclusive legacy book/chapter identities cannot be overridden"))
        assertTrue(confirmIdentity.path("responses").path("409").path("description").asText().contains("old identity policy"))
        val selectVersion = paths.path("/api/v1/recovery-batches/{batchId}/uploads/{uploadId}/select").path("post")
        assertTrue(selectVersion.path("responses").has("200"))
        assertTrue(selectVersion.path("responses").has("409"))
        assertTrue(selectVersion.path("description").asText().contains("one exact asset version"))
        assertTrue(selectVersion.path("responses").path("409").path("description").asText().contains("identity policy is outdated"))
        val latestValidation = paths.path("/api/v1/recovery-batches/{batchId}/uploads/{uploadId}/validation").path("get")
        assertTrue(latestValidation.path("responses").has("200"))
        assertTrue(latestValidation.path("responses").has("204"))
        assertTrue(latestValidation.path("responses").path("204").path("description").asText().contains("current identity policy"))
        val removeUpload = paths.path("/api/v1/recovery-batches/{batchId}/uploads/{uploadId}").path("delete")
        assertTrue(removeUpload.path("responses").has("200"))
        assertTrue(removeUpload.path("responses").has("404"))

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
        assertTrue(snapshotProperties.has("externalProviderConsents"))
        val consentSnapshotSchema = snapshotProperties.path("externalProviderConsents").path("items").path("${'$'}ref").asText().substringAfterLast('/')
        val consentSnapshotProperties = document.path("components").path("schemas").path(consentSnapshotSchema).path("properties")
        assertTrue(consentSnapshotProperties.has("providerId"))
        assertTrue(consentSnapshotProperties.has("dataCategories"))
        assertTrue(consentSnapshotProperties.has("retentionDisclosure"))
        assertTrue(snapshotProperties.has("sourceParser"))
        assertTrue(snapshotProperties.has("citedPaperParser"))
        assertTrue(snapshotProperties.has("validationLimits"))
        val limitsSchemaName = snapshotProperties.path("validationLimits").path("${'$'}ref").asText().substringAfterLast('/')
        val limitsProperties = document.path("components").path("schemas").path(limitsSchemaName).path("properties")
        assertTrue(limitsProperties.has("maxClaimCitationPairs"))
        assertTrue(runProperties.path("failureReason").path("description").asText().contains("observed pair count"))
        val parsedDocument = paths.path("/api/v1/analysis-runs/{runId}/parsed-document").path("get")
        assertTrue(parsedDocument.path("responses").path("200").path("content").has("application/json"))
        assertTrue(parsedDocument.path("summary").asText().contains("Atomic Claims"))
        assertTrue(parsedDocument.path("description").asText().contains("inferred/provisional"))
        assertTrue(parsedDocument.path("description").asText().contains("Citation Context"))
        assertTrue(parsedDocument.path("description").asText().contains("PARSED"))
        assertFalse(parsedDocument.path("description").asText().contains("completed Analysis Run"))
        assertTrue(parsedDocument.path("responses").has("404"))
        assertTrue(parsedDocument.path("responses").has("409"))
        val parsedDocumentSchemaName = parsedDocument.path("responses").path("200").path("content").path("application/json").path("schema").path("${'$'}ref").asText().substringAfterLast('/')
        val parsedDocumentProperties = document.path("components").path("schemas").path(parsedDocumentSchemaName).path("properties")
        assertTrue(parsedDocumentProperties.has("bibliographyNormalizationPolicy"))
        val normalizationPolicySchemaName = parsedDocumentProperties.path("bibliographyNormalizationPolicy").path("oneOf").path(0).path("${'$'}ref").asText().substringAfterLast('/')
        val normalizationPolicyProperties = document.path("components").path("schemas").path(normalizationPolicySchemaName).path("properties")
        assertTrue(
            normalizationPolicyProperties.path("version").path("description").asText().contains("Version 3"),
            normalizationPolicyProperties.toPrettyString(),
        )
        val parsedBibliographySchemaName = parsedDocumentProperties.path("bibliographyEntries").path("items").path("${'$'}ref").asText().substringAfterLast('/')
        val parsedBibliographyProperties = document.path("components").path("schemas").path(parsedBibliographySchemaName).path("properties")
        assertTrue(parsedBibliographyProperties.path("referenceType").path("description").asText().contains("policy v3"))
        assertTrue(parsedBibliographyProperties.path("referenceType").path("description").asText().contains("earlier policy versions may retain BOOK"))
        assertTrue(parsedBibliographyProperties.path("sourceTextContent").path("description").asText().contains("Unnormalized"))
        assertTrue(parsedBibliographyProperties.path("provisionalArtifactSignals").path("description").asText().contains("not human adjudication"))
        assertTrue(parsedBibliographyProperties.has("identifiers"))
        assertTrue(parsedBibliographyProperties.has("extractionLimitations"))
        val occurrenceProperties = document.path("components").path("schemas").path("ParsedCitationOccurrenceView").path("properties")
        assertTrue(occurrenceProperties.has("unmatchedBibliographyReferenceKeys"))
        val sourceDocument = paths.path("/api/v1/analysis-runs/{runId}/source-document").path("get")
        assertTrue(sourceDocument.path("parameters").any { it.path("name").asText() == "runId" && it.path("description").asText().contains("Analysis Run") })
        val sourcePdfResponse = sourceDocument.path("responses").path("200")
        assertTrue(sourcePdfResponse.path("content").has("application/json"))
        assertTrue(sourcePdfResponse.path("headers").path("Cache-Control").path("description").asText().contains("bearer URLs"))
        val sourcePdfSchemaName = sourcePdfResponse.path("content").path("application/json").path("schema").path("${'$'}ref").asText().substringAfterLast('/')
        val sourcePdfProperties = document.path("components").path("schemas").path(sourcePdfSchemaName).path("properties")
        assertTrue(sourcePdfProperties.has("filename"))
        assertTrue(sourcePdfProperties.path("viewUrl").path("description").asText().contains("S3-compatible presigned bearer URL"))
        assertTrue(sourcePdfProperties.path("downloadUrl").path("description").asText().contains("S3-compatible presigned bearer URL"))
        assertTrue(sourcePdfProperties.has("expiresAt"))
        assertTrue(sourceDocument.path("responses").has("404"))
        assertTrue(sourceDocument.path("responses").has("409"))
        assertTrue(sourceDocument.path("responses").has("503"))
        val deleteDocument = paths.path("/api/v1/documents/{documentId}").path("delete")
        assertEquals("Delete a Source Document and all document-scoped data", deleteDocument.path("summary").asText())
        assertTrue(deleteDocument.path("responses").has("204"))
        assertTrue(deleteDocument.path("responses").has("404"))
        assertTrue(deleteDocument.path("responses").has("503"))
        assertTrue(deleteDocument.path("description").asText().contains("cannot be retracted"))
        val reanalysis = paths.path("/api/v1/documents/{documentId}/analysis-runs").path("post")
        assertTrue(reanalysis.path("description").asText().contains("server-issued retention-disclosure fingerprint"))
        val runConfigurationSchemaName = reanalysis.path("requestBody").path("content").path("application/json")
            .path("schema").path("${'$'}ref").asText().substringAfterLast('/')
        val runConfigurationProperties = document.path("components").path("schemas")
            .path(runConfigurationSchemaName).path("properties")
        val externalConsentSchemaName = runConfigurationProperties.path("externalProviderConsents")
            .path("items").path("${'$'}ref").asText().substringAfterLast('/')
        val externalConsentProperties = document.path("components").path("schemas")
            .path(externalConsentSchemaName).path("properties")
        assertTrue(externalConsentProperties.has("providerId"))
        assertTrue(externalConsentProperties.has("dataCategories"))
        assertEquals("^[0-9a-f]{64}$", externalConsentProperties.path("retentionDisclosureFingerprint").path("pattern").asText())
        val report = paths.path("/api/v1/analysis-runs/{runId}/report").path("get")
        assertTrue(report.path("responses").path("200").path("content").has("application/json"))
        assertTrue(report.path("responses").has("404"))
        val reportSchemaName = report.path("responses").path("200").path("content").path("application/json").path("schema").path("${'$'}ref").asText().substringAfterLast('/')
        val reportProperties = document.path("components").path("schemas").path(reportSchemaName).path("properties")
        val resolutionSchemaName = reportProperties.path("referenceResolution").path("${'$'}ref").asText().substringAfterLast('/')
        val resolutionProperties = document.path("components").path("schemas").path(resolutionSchemaName).path("properties")
        assertTrue(resolutionProperties.has("bibliographyNormalizationPolicy"))
        assertTrue(resolutionProperties.path("summary").path("${'$'}ref").asText().isNotBlank())
        val reportEntrySchemaName = resolutionProperties.path("entries").path("items").path("${'$'}ref").asText().substringAfterLast('/')
        val reportEntryProperties = document.path("components").path("schemas").path(reportEntrySchemaName).path("properties")
        assertTrue(reportEntryProperties.path("provisionalArtifactSignals").path("description").asText().contains("not adjudicated"))
        assertTrue(reportEntryProperties.path("confidenceScore").path("description").asText().contains("not a probability"))
        assertTrue(reportEntryProperties.path("confidenceScore").path("description").asText().contains("DOI-confirmed"))
        assertTrue(reportEntryProperties.path("reasonCode").path("description").asText().contains("CONFLICTING_DOI_IDENTIFIERS"))
        assertTrue(reportEntryProperties.has("sourceLocalReferenceKey"))
        assertTrue(reportEntryProperties.has("identifiers"))
        val candidateEvidence = reportEntryProperties.path("candidateEvidence")
        assertTrue(candidateEvidence.path("description").asText().contains("Up to three"))
        val candidateSchemaName = candidateEvidence.path("items").path("${'$'}ref").asText().substringAfterLast('/')
        val candidateProperties = document.path("components").path("schemas").path(candidateSchemaName).path("properties")
        assertTrue(candidateProperties.path("rankingScore").path("description").asText().contains("not a probability"))
        assertTrue(candidateProperties.has("reasonCodes"))
        assertTrue(report.path("description").asText().contains("never rolled up"))
        assertTrue(report.path("description").asText().contains("access-stage skip reasons"))
        val accessProgressStatusSchema = document.findValue("accessProgressStatus")
        val accessProgressReasonSchema = document.findValue("accessProgressReason")
        val accessReasonsSchema = document.findValue("accessReasons")
        assertTrue(accessProgressStatusSchema.path("description").asText().contains("legacy history"))
        assertTrue(accessProgressStatusSchema.path("enum").any { it.asText() == "SKIPPED" })
        assertTrue(accessProgressReasonSchema.path("description").asText().contains("historical cause"))
        assertTrue(accessProgressReasonSchema.path("enum").any { it.asText() == "ACCESS_SKIPPED_IDENTITY_UNRESOLVED" })
        assertTrue(accessReasonsSchema.path("description").asText().contains("detailed attribution"))
        assertTrue(accessReasonsSchema.path("items").path("enum").any { it.asText() == "FULL_TEXT_PARSE_FAILED" })
        assertTrue(accessReasonsSchema.path("items").path("enum").any { it.asText() == "LANGUAGE_UNSUPPORTED" })
        val review = paths.path("/api/v1/verifications/{verificationId}/reviews").path("post")
        assertEquals("Record a Human Review", review.path("summary").asText())
        assertTrue(review.path("requestBody").path("content").has("application/json"))
        assertTrue(review.path("responses").has("201"))
        assertTrue(review.path("responses").has("400"))
        assertTrue(review.path("responses").has("404"))
        assertTrue(review.path("responses").has("409"))
        val reviewRequestSchemaName = review.path("requestBody").path("content").path("application/json").path("schema").path("${'$'}ref").asText().substringAfterLast('/')
        val reviewRequestProperties = document.path("components").path("schemas").path(reviewRequestSchemaName).path("properties")
        assertTrue(reviewRequestProperties.has("action"))
        assertTrue(reviewRequestProperties.has("overrideStatus"))
        assertTrue(reviewRequestProperties.has("note"))
        val schemas = document.path("components").path("schemas")
        val verificationProperties = schemas.path("CitedReferenceVerificationOutcome").path("properties")
        assertTrue(verificationProperties.has("finalStatus"))
        assertTrue(verificationProperties.path("processingFailureReason").path("description").asText().contains("SYSTEM_ONE_HTTP_<status>"))
        assertTrue(verificationProperties.path("processingFailureReason").path("description").asText().contains("SYSTEM_ONE_RESPONSE_*"))
        assertTrue(verificationProperties.path("processingFailureReason").path("description").asText().contains("never contains provider response bodies"))
        assertTrue(verificationProperties.has("humanReviews"))
        val passageProperties = schemas.path("EvidencePassageReport").path("properties")
        assertTrue(passageProperties.has("diagnosticSpans"))
        assertTrue(passageProperties.path("diagnosticSpans").path("description").asText().contains("never rolled up"))
        val spanProperties = schemas.path("EvidencePassageSpanReport").path("properties")
        assertTrue(spanProperties.has("coreStartOffset"))
        assertTrue(spanProperties.has("coreEndOffset"))
        assertTrue(spanProperties.has("contextStartOffset"))
        assertTrue(spanProperties.has("contextEndOffset"))
        assertTrue(spanProperties.has("tokenCounts"))
        assertTrue(spanProperties.has("failureReason"))
        assertTrue(spanProperties.has("judgementRubricVersion"))
        assertTrue(spanProperties.has("splittingPolicyVersion"))
        assertTrue(spanProperties.path("coreStartOffset").path("description").asText().contains("original Evidence Passage"))
        val humanReviewProperties = schemas.path("HumanReview").path("properties")
        assertTrue(humanReviewProperties.has("analysisRunId"))
        assertTrue(humanReviewProperties.has("verificationId"))
        assertTrue(humanReviewProperties.has("action"))
        assertTrue(humanReviewProperties.has("overrideStatus"))
        assertTrue(humanReviewProperties.has("note"))
        assertTrue(humanReviewProperties.has("createdAt"))
        val reanalysisSchema = reanalysis.path("requestBody").path("content").path("application/json").path("schema")
        val configSchemaName = reanalysisSchema.path("${'$'}ref").asText().substringAfterLast('/')
        val configProperties = document.path("components").path("schemas").path(configSchemaName).path("properties")
        assertTrue(configProperties.has("claimExtractorProvider"))
        assertEquals("openai-compatible-chat", configProperties.path("claimExtractorProvider").path("default").asText())
        assertTrue(configProperties.path("claimExtractorProvider").path("description").asText().contains("deployment-configured default"))
        assertTrue(configProperties.has("embeddingProvider"))
        assertEquals("ollama", configProperties.path("embeddingProvider").path("default").asText())
        assertTrue(configProperties.path("embeddingProvider").path("description").asText().contains("local Ollama"))
        assertTrue(configProperties.has("systemOneProvider"))
        val systemOneDescription = configProperties.path("systemOneProvider").path("description").asText()
        assertTrue(systemOneDescription.contains("Jev by default"))
        assertTrue(systemOneDescription.contains("per-Analysis-Run consent"))
        assertTrue(configProperties.has("scholarlyMetadataProvider"))
        assertTrue(configProperties.has("openAccessProvider"))
        assertTrue(configProperties.has("externalProviderConsents"))
        assertTrue(configProperties.has("captureExecution"))
        assertTrue(configProperties.path("captureExecution").path("description").asText().contains("does not grant additional external-provider consent"))
        val consentRequestSchema = configProperties.path("externalProviderConsents").path("items").path("${'$'}ref").asText().substringAfterLast('/')
        val consentRequestProperties = document.path("components").path("schemas").path(consentRequestSchema).path("properties")
        assertTrue(consentRequestProperties.has("providerId"))
        assertTrue(consentRequestProperties.has("dataCategories"))
        assertTrue(consentRequestProperties.has("retentionDisclosureFingerprint"))
        assertFalse(consentRequestProperties.has("retentionDisclosure"))
        assertTrue(reanalysis.path("description").asText().contains("server-issued"))
        assertTrue(reanalysis.path("responses").has("201"))
        assertTrue(reanalysis.path("responses").has("404"))
        val executionBase = "/api/v1/analysis-runs/{runId}/execution"
        val executionSummary = paths.path(executionBase).path("get")
        assertTrue(executionSummary.path("responses").has("200"))
        assertTrue(executionSummary.path("responses").has("404"))
        val executionSummarySchema = executionSummary.path("responses").path("200").path("content")
            .path("application/json").path("schema").path("${'$'}ref").asText().substringAfterLast('/')
        val executionSummaryProperties = schemas.path(executionSummarySchema).path("properties")
        listOf("analysisRunId", "captureRequested", "captureEnabled", "recordingState", "completeness", "startedAt", "finishedAt", "totalDurationMillis", "gapReason")
            .forEach { assertTrue(executionSummaryProperties.has(it)) }
        assertTrue(executionSummaryProperties.path("gapReason").path("enum").any { it.asText() == "UNSAFE_SPAN_METADATA_OMITTED" })
        val spanList = paths.path("$executionBase/spans").path("get")
        assertTrue(spanList.path("responses").path("200").path("content").has("application/json"))
        assertTrue(spanList.path("parameters").any { it.path("name").asText() == "cursor" })
        val spanPageSchemaName = spanList.path("responses").path("200").path("content").path("application/json")
            .path("schema").path("${'$'}ref").asText().substringAfterLast('/')
        val spanPageProperties = schemas.path(spanPageSchemaName).path("properties")
        assertTrue(spanPageProperties.has("items"))
        assertTrue(spanPageProperties.has("nextCursor"))
        val spanSchemaName = spanPageProperties.path("items").path("items").path("${'$'}ref").asText().substringAfterLast('/')
        val executionSpanProperties = schemas.path(spanSchemaName).path("properties")
        listOf("id", "parentSpanId", "operationId", "stageId", "kind", "name", "startedAt", "endedAt", "durationMillis", "status", "attempt", "providerId", "modelId", "httpStatus", "safeErrorCode", "attributes", "trustBoundary", "httpRoute", "domainLinks", "artifactRoles")
            .forEach { assertTrue(executionSpanProperties.has(it)) }
        val spanDetail = paths.path("$executionBase/spans/{spanId}").path("get")
        assertTrue(spanDetail.path("responses").has("200"))
        assertTrue(spanDetail.path("responses").has("404"))
        val executionArtifact = paths.path("$executionBase/artifacts/{artifactId}").path("get")
        assertTrue(executionArtifact.path("responses").path("200").path("headers").has("Cache-Control"))
        assertTrue(executionArtifact.path("responses").has("400"))
        assertTrue(executionArtifact.path("responses").has("404"))
        assertTrue(executionArtifact.path("parameters").any { it.path("name").asText() == "spanId" && it.path("in").asText() == "query" })
        assertTrue(executionArtifact.path("parameters").any { it.path("name").asText() == "role" && it.path("in").asText() == "query" })
        val artifactSchemaName = executionArtifact.path("responses").path("200").path("content")
            .path("application/json").path("schema").path("${'$'}ref").asText().substringAfterLast('/')
        val artifactProperties = schemas.path(artifactSchemaName).path("properties")
        listOf("id", "spanId", "role", "fidelity", "reason", "mediaType", "content", "schemaVersion", "captureVersion", "sanitizerVersion", "sizeBytes")
            .forEach { assertTrue(artifactProperties.has(it)) }
        val artifactDescriptorSchema = executionSpanProperties.path("artifactRoles").path("items").path("${'$'}ref").asText().substringAfterLast('/')
        val artifactDescriptorProperties = schemas.path(artifactDescriptorSchema).path("properties")
        listOf("id", "role", "fidelity", "reason", "mediaType", "sizeBytes").forEach { assertTrue(artifactDescriptorProperties.has(it)) }
        listOf("id", "mediaType", "sizeBytes").forEach { field ->
            val property = artifactDescriptorProperties.path(field)
            val nullableType = property.path("type").isArray && property.path("type").any { it.asText() == "null" }
            assertTrue(property.path("nullable").asBoolean() || nullableType, "$field must allow null: $property")
        }
        assertEquals(setOf("INPUT", "REQUEST", "RESPONSE", "RESULT"), artifactDescriptorProperties.path("role").path("enum").map { it.asText() }.toSet())
        assertFalse(executionSpanProperties.has("artifactDescriptors"))
        assertTrue(paths.path("$executionBase/capture").path("patch").path("responses").has("409"))
        assertTrue(paths.path("$executionBase/artifacts/{artifactId}").path("delete").path("responses").has("204"))
        assertTrue(paths.path("/api/v1/health").path("get").path("responses").path("200").path("content").has("application/json"))
        val invalidateCrossref = paths.path("/api/v1/operator/caches/crossref").path("delete")
        assertEquals("Invalidate one Crossref cache entry", invalidateCrossref.path("summary").asText())
        assertTrue(invalidateCrossref.path("responses").has("200"))
        assertTrue(invalidateCrossref.path("responses").has("400"))
        assertTrue(invalidateCrossref.path("responses").has("401"))
        val operatorCredential = invalidateCrossref.path("parameters").first { it.path("name").asText() == "X-Operator-Credential" }
        assertTrue(operatorCredential.path("required").asBoolean())
        val invalidationSchemaName = invalidateCrossref.path("requestBody").path("content").path("application/json").path("schema").path("${'$'}ref").asText().substringAfterLast('/')
        val invalidationProperties = document.path("components").path("schemas").path(invalidationSchemaName).path("properties")
        assertTrue(invalidationProperties.has("lookupType"))
        assertTrue(invalidationProperties.has("doi"))
        assertTrue(invalidationProperties.has("query"))
        assertTrue(invalidateCrossref.path("description").asText().contains("provider-wide invalidation"))
        val invalidateUnpaywall = paths.path("/api/v1/operator/caches/unpaywall").path("delete")
        assertEquals("Invalidate one Unpaywall DOI cache entry", invalidateUnpaywall.path("summary").asText())
        assertTrue(invalidateUnpaywall.path("responses").has("200"))
        assertTrue(invalidateUnpaywall.path("responses").has("400"))
        assertTrue(invalidateUnpaywall.path("responses").has("401"))
        assertTrue(invalidateUnpaywall.path("responses").has("503"))
        assertTrue(invalidateUnpaywall.path("parameters").any { it.path("name").asText() == "X-Operator-Credential" })
        val unpaywallSchemaName = invalidateUnpaywall.path("requestBody").path("content").path("application/json").path("schema").path("${'$'}ref").asText().substringAfterLast('/')
        val unpaywallProperties = document.path("components").path("schemas").path(unpaywallSchemaName).path("properties")
        assertEquals(setOf("doi"), unpaywallProperties.fieldNames().asSequence().toSet())
        assertTrue(invalidateUnpaywall.path("description").asText().contains("exactly one normalized Unpaywall DOI"))
    }

    @Test
    fun `document deletion endpoint delegates confirmed deletion and returns no content`() {
        val documentId = UUID.randomUUID()

        mockMvc.perform(delete("/api/v1/documents/$documentId"))
            .andExpect(status().isNoContent)

        Mockito.verify(sourceDocumentDeletionService).delete(documentId)
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
    fun `operator cache invalidation remains disabled without a configured server credential`() {
        val service = CrossrefCacheInvalidationService(crossrefLookupCache, OperatorCredentialVerifier(""))
        val exception = assertThrows(ResponseStatusException::class.java) {
            service.invalidate("anything", CrossrefCacheInvalidationRequest(CrossrefCacheLookupType.DOI, doi = "10.1234/one"))
        }
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, exception.statusCode)
        Mockito.verifyNoInteractions(crossrefLookupCache)
    }

    @Test
    fun `operator cache invalidation rejects missing credentials and invalidates only the requested DOI`() {
        val body = """{"lookupType":"DOI","doi":"https://doi.org/10.1234/operator-entry"}"""
        mockMvc.perform(
            delete("/api/v1/operator/caches/crossref")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body),
        ).andExpect(status().isUnauthorized)
        Mockito.verifyNoInteractions(crossrefLookupCache)

        Mockito.`when`(crossrefLookupCache.invalidateDoi("https://doi.org/10.1234/operator-entry")).thenReturn(true)
        mockMvc.perform(
            delete("/api/v1/operator/caches/crossref")
                .header("X-Operator-Credential", "operator-test-credential")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body),
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.invalidated").value(true))
        Mockito.verify(crossrefLookupCache).invalidateDoi("https://doi.org/10.1234/operator-entry")
        Mockito.verifyNoMoreInteractions(crossrefLookupCache)
    }

    @Test
    fun `operator cache invalidation targets one normalized search entry`() {
        Mockito.`when`(crossrefLookupCache.invalidateSearch("A title Ada 2024")).thenReturn(true)
        mockMvc.perform(
            delete("/api/v1/operator/caches/crossref")
                .header("X-Operator-Credential", "operator-test-credential")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"lookupType":"SEARCH","query":"A title Ada 2024"}"""),
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.invalidated").value(true))
        Mockito.verify(crossrefLookupCache).invalidateSearch("A title Ada 2024")
        Mockito.verifyNoMoreInteractions(crossrefLookupCache)
    }

    @Test
    fun `operator cache invalidation rejects malformed or provider-wide requests`() {
        val malformedRequests = listOf(
            """{"lookupType":"DOI","doi":"not-a-doi"}""",
            """{"lookupType":"DOI","doi":"10.1234/one","query":"also supplied"}""",
            """{"lookupType":"ALL","doi":"10.1234/one"}""",
        )
        malformedRequests.forEach { body ->
            mockMvc.perform(
                delete("/api/v1/operator/caches/crossref")
                    .header("X-Operator-Credential", "operator-test-credential")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body),
            ).andExpect(status().isBadRequest)
        }
        Mockito.verifyNoInteractions(crossrefLookupCache)
    }

    @Test
    fun `operator Unpaywall invalidation requires credentials and removes only the requested DOI key`() {
        mockMvc.perform(
            delete("/api/v1/operator/caches/unpaywall")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"doi":"10.1234/operator-entry"}"""),
        ).andExpect(status().isUnauthorized)
        Mockito.verifyNoInteractions(unpaywallDiscoveryCache)

        Mockito.`when`(unpaywallDiscoveryCache.invalidateDoi("https://doi.org/10.1234/operator-entry")).thenReturn(true)
        mockMvc.perform(
            delete("/api/v1/operator/caches/unpaywall")
                .header("X-Operator-Credential", "operator-test-credential")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"doi":"https://doi.org/10.1234/operator-entry"}"""),
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.invalidated").value(true))
        Mockito.verify(unpaywallDiscoveryCache).invalidateDoi("https://doi.org/10.1234/operator-entry")
        Mockito.verifyNoMoreInteractions(unpaywallDiscoveryCache)
    }

    @Test
    fun `operator Unpaywall invalidation rejects invalid DOI requests`() {
        listOf("", "not-a-doi").forEach { doi ->
            mockMvc.perform(
                delete("/api/v1/operator/caches/unpaywall")
                    .header("X-Operator-Credential", "operator-test-credential")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(UnpaywallCacheInvalidationRequest(doi))),
            ).andExpect(status().isBadRequest)
        }
        Mockito.verifyNoInteractions(unpaywallDiscoveryCache)

        val service = UnpaywallCacheInvalidationService(unpaywallDiscoveryCache, OperatorCredentialVerifier(""))
        assertThrows(ResponseStatusException::class.java) {
            service.invalidate("anything", UnpaywallCacheInvalidationRequest("10.1234/entry"))
        }
        Mockito.verifyNoInteractions(unpaywallDiscoveryCache)
    }

    @Test
    fun `provider directory exposes configured external options and stable category descriptions`() {
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
        assertEquals(2, claimExtractorOptions.size())
        assertEquals(
            setOf("heuristic", "openai-compatible-chat"),
            claimExtractorOptions.map { it.path("providerId").asText() }.toSet(),
        )
        val localClaimAnalyzer = claimExtractorOptions.first { it.path("providerId").asText() == "openai-compatible-chat" }
        assertEquals("google/gemma-4-e2b", localClaimAnalyzer.path("model").asText())
        assertEquals("LOCAL", localClaimAnalyzer.path("trustBoundary").asText())
        assertEquals(1, embeddingOptions.size())
        assertEquals(1, systemOneOptions.size())
        assertEquals(2, scholarlyMetadataOptions.size())
        assertEquals(2, openAccessOptions.size())
        val providerOptions = listOf(claimExtractorOptions, embeddingOptions, systemOneOptions, scholarlyMetadataOptions, openAccessOptions).flatMap { it.toList() }
        assertEquals(8, providerOptions.size)
        assertEquals(setOf("recorded-fixtures", "crossref"), scholarlyMetadataOptions.map { it.path("providerId").asText() }.toSet())
        assertEquals(setOf("recorded-fixtures", "unpaywall"), openAccessOptions.map { it.path("providerId").asText() }.toSet())
        val crossref = scholarlyMetadataOptions.first { it.path("providerId").asText() == "crossref" }
        val unpaywall = openAccessOptions.first { it.path("providerId").asText() == "unpaywall" }
        assertEquals("EXTERNAL", crossref.path("trustBoundary").asText())
        assertEquals("EXTERNAL", unpaywall.path("trustBoundary").asText())
        assertTrue(crossref.path("retentionDisclosure").asText().isNotBlank())
        assertTrue(unpaywall.path("retentionDisclosure").asText().isNotBlank())
        assertFalse(providerOptions.any { it.path("providerId").asText() in setOf("jev", "google-gemini-api", "unclassified-provider") })
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
    fun `Human Review endpoint returns the appended separate assessment`() {
        val verificationId = UUID.randomUUID()
        val runId = UUID.randomUUID()
        val review = HumanReview(
            id = UUID.randomUUID(),
            analysisRunId = runId,
            verificationId = verificationId,
            action = HumanReviewAction.OVERRIDE,
            overrideStatus = TerminalVerificationStatus.SUPPORTED,
            note = "Separate human assessment.",
            createdAt = Instant.parse("2026-01-02T03:04:05Z"),
        )
        Mockito.`when`(humanReviewService.record(
            verificationId,
            HumanReviewAction.OVERRIDE,
            TerminalVerificationStatus.SUPPORTED,
            "Separate human assessment.",
        )).thenReturn(review)

        val response = mockMvc.perform(
            post("/api/v1/verifications/$verificationId/reviews")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(CreateHumanReviewRequest(
                    action = HumanReviewAction.OVERRIDE,
                    overrideStatus = TerminalVerificationStatus.SUPPORTED,
                    note = "Separate human assessment.",
                ))),
        )
            .andExpect(status().isCreated)
            .andReturn()
            .response
        val body = objectMapper.readTree(response.contentAsString)
        assertEquals(review.id.toString(), body.path("id").asText())
        assertEquals(runId.toString(), body.path("analysisRunId").asText())
        assertEquals(verificationId.toString(), body.path("verificationId").asText())
        assertEquals("OVERRIDE", body.path("action").asText())
        assertEquals("SUPPORTED", body.path("overrideStatus").asText())
        assertEquals("Separate human assessment.", body.path("note").asText())
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
                            UUID.randomUUID(), "[1]", 6, 9, listOf("ref1"), listOf("missing-tei-key"),
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
            bibliographyNormalizationPolicy = BibliographyNormalizationPolicySelection.CURRENT,
        )
        Mockito.`when`(analysisRunService.getParsedDocument(runId)).thenReturn(parsed)

        mockMvc.perform(get("/api/v1/analysis-runs/$runId/parsed-document"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.parser.provider").value("grobid"))
            .andExpect(jsonPath("$.normalizedSourceText").value("Claim [1]."))
            .andExpect(jsonPath("$.citationContexts[0].occurrences[0].bibliographyReferenceKeys[0]").value("ref1"))
            .andExpect(jsonPath("$.citationContexts[0].occurrences[0].unmatchedBibliographyReferenceKeys[0]").value("missing-tei-key"))
            .andExpect(jsonPath("$.bibliographyNormalizationPolicy.version").value("3"))
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
        val outcome = CitedReferenceVerificationOutcome(
            id = UUID.randomUUID(),
            atomicClaimId = UUID.randomUUID(),
            claimText = "The study reports an outcome.",
            claimSourceStartOffset = 0,
            claimSourceEndOffset = 32,
            citationContextText = "The study reports an outcome [1].",
            citationMarkers = listOf("[1]"),
            associationKind = "INFERRED_PROVISIONAL",
            processingStatus = "COMPLETED",
            processingFailureReason = null,
            finalStatus = "INSUFFICIENT_EVIDENCE",
            verificationScope = "ABSTRACT_ONLY",
            terminalReason = "ABSTRACT_ONLY",
            evidenceConflict = false,
            aggregatorVersion = "conflict-aware-evidence-strength-v1",
        )
        Mockito.`when`(analysisRunReportService.report(runId)).thenReturn(
            ReferenceResolutionReportResponse(
                analysisRunId = runId,
                runStatus = "COMPLETED",
                evidenceCoverage = EvidenceCoverageReport(
                    executionStatus = "COMPLETED",
                    verificationPolicyVersion = "weighted-evidence-role-scope-design-v1",
                    aggregationPolicyVersion = "conflict-aware-evidence-strength-v1",
                    thresholds = mapOf("directSupport" to 0.8, "partialSupport" to 0.7, "contradiction" to 0.8, "comparabilityMargin" to 0.08),
                    summary = EvidenceCoverageSummary(totalVerifications = 1, completedVerifications = 1, insufficientEvidence = 1),
                ),
                referenceResolution = ReferenceResolutionReport(
                    executionStatus = "COMPLETED",
                    scorePolicyVersion = "title-author-year-weighted-edit-similarity-v1",
                    confidenceThreshold = 0.9,
                    summary = ReferenceResolutionSummary(2, 1, 0, 1, 0, 0, provisionalArtifactSignalEntries = 1),
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
                            canonicalPaper = ReportCanonicalPaper(
                                id = UUID.randomUUID(),
                                doi = "10.1234/example",
                                title = "Canonical Example Paper",
                                authors = listOf("Riley Example"),
                                year = 2024,
                            ),
                            confidenceScore = 1.0,
                            matchMethod = "DOI",
                            candidateEvidence = listOf(
                                ScholarlyCandidateEvidence(
                                    doi = "10.1234/example",
                                    title = "Example paper",
                                    authors = listOf("Riley Example"),
                                    year = 2024,
                                    rankingScore = 1.0,
                                    reasonCodes = listOf("TITLE_EXACT", "AUTHOR_SET_MATCH", "YEAR_MATCH", "DOI_CONFIRMED"),
                                ),
                            ),
                            sourceTextContent = "Original GROBID source text",
                            sourceElement = "biblStruct",
                            sourceLocalReferenceKey = "ref1",
                            localReferenceKeyOrigin = "GROBID_XML_ID",
                            identifiers = listOf(ParsedBibliographyIdentifier("idno", "DOI", "https://doi.org/10.1234/example", "10.1234/example")),
                            sourceLocations = listOf(ParsedBibliographySourceLocation(2, "2,10,20,30,40")),
                            provisionalArtifactSignals = listOf("UNCITED_SECTION_HEADING_PATTERN"),
                            extractionLimitations = listOf("SOURCE_TEXT_SPAN_UNAVAILABLE"),
                            provenanceCaptureStatus = "CAPTURED",
                            accessProgressStatus = "COMPLETED",
                            accessProgressReason = null,
                            citedPaperAccess = CitedPaperAccessReport(
                                accessStatus = "ABSTRACT_ONLY",
                                accessReason = "ABSTRACT_ONLY",
                                accessReasons = listOf("NO_FULL_TEXT_LOCATION_RETURNED"),
                                providerId = "recorded-fixtures",
                                sourceUrl = null,
                                license = null,
                                version = null,
                                hostType = null,
                                discoveredAt = Instant.parse("2025-01-01T00:00:00Z"),
                                contentSha256 = null,
                                language = null,
                                languageDetectorVersion = null,
                                verificationOutcomes = listOf(outcome),
                            ),
                            verificationOutcomes = listOf(outcome),
                        ),
                    ),
                    bibliographyNormalizationPolicy = BibliographyNormalizationPolicySelection.CURRENT,
                ),
            ),
        )

        mockMvc.perform(get("/api/v1/analysis-runs/$runId/report"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.evidenceCoverage.executionStatus").value("COMPLETED"))
            .andExpect(jsonPath("$.evidenceCoverage.summary.totalVerifications").value(1))
            .andExpect(jsonPath("$.evidenceCoverage.summary.incompleteVerifications").value(0))
            .andExpect(jsonPath("$.evidenceCoverage.triageDisclaimer").exists())
            .andExpect(jsonPath("$.referenceResolution.executionStatus").value("COMPLETED"))
            .andExpect(jsonPath("$.referenceResolution.scorePolicyVersion").value("title-author-year-weighted-edit-similarity-v1"))
            .andExpect(jsonPath("$.referenceResolution.confidenceThreshold").value(0.9))
            .andExpect(jsonPath("$.referenceResolution.summary.unsupportedReferenceType").value(1))
            .andExpect(jsonPath("$.referenceResolution.summary.failed").value(0))
            .andExpect(jsonPath("$.referenceResolution.summary.provisionalArtifactSignalEntries").value(1))
            .andExpect(jsonPath("$.referenceResolution.bibliographyNormalizationPolicy.version").value("3"))
            .andExpect(jsonPath("$.referenceResolution.entries[0].candidateEvidence[0].title").value("Example paper"))
            .andExpect(jsonPath("$.referenceResolution.entries[0].candidateEvidence[0].rankingScore").value(1.0))
            .andExpect(jsonPath("$.referenceResolution.entries[0].candidateEvidence[0].reasonCodes[0]").value("TITLE_EXACT"))
            .andExpect(jsonPath("$.referenceResolution.entries[0].sourceTextContent").value("Original GROBID source text"))
            .andExpect(jsonPath("$.referenceResolution.entries[0].sourceLocalReferenceKey").value("ref1"))
            .andExpect(jsonPath("$.referenceResolution.entries[0].identifiers[0].rawValue").value("https://doi.org/10.1234/example"))
            .andExpect(jsonPath("$.referenceResolution.entries[0].provisionalArtifactSignals[0]").value("UNCITED_SECTION_HEADING_PATTERN"))
            .andExpect(jsonPath("$.referenceResolution.entries[0].citedPaperAccess.accessStatus").value("ABSTRACT_ONLY"))
            .andExpect(jsonPath("$.referenceResolution.entries[0].citedPaperAccess.accessReason").value("ABSTRACT_ONLY"))
            .andExpect(jsonPath("$.referenceResolution.entries[0].citedPaperAccess.accessReasons[0]").value("NO_FULL_TEXT_LOCATION_RETURNED"))
            .andExpect(jsonPath("$.referenceResolution.entries[0].accessProgressStatus").value("COMPLETED"))
            .andExpect(jsonPath("$.referenceResolution.entries[0].citedPaperAccess.verificationOutcomes[0].claimText").value("The study reports an outcome."))
            .andExpect(jsonPath("$.referenceResolution.entries[0].canonicalPaper.title").value("Canonical Example Paper"))
            .andExpect(jsonPath("$.referenceResolution.entries[0].verificationOutcomes[0].claimText").value("The study reports an outcome."))
            .andExpect(jsonPath("$.referenceResolution.entries[0].verificationOutcomes[0].finalStatus").value("INSUFFICIENT_EVIDENCE"))
            .andExpect(jsonPath("$.referenceResolution.entries[0].verificationOutcomes[0].citationMarkers[0]").value("[1]"))
            .andExpect(jsonPath("$.referenceResolution.entries[0].verificationOutcomes[0].processingStatus").value("COMPLETED"))
    }

    @Test
    fun `validation endpoint returns persisted metadata provenance and separate outcomes`() {
        val batchId = UUID.randomUUID()
        val uploadId = UUID.randomUUID()
        val attempt = RecoveryUploadValidationAttempt(
            id = UUID.randomUUID(),
            batchId = batchId,
            uploadId = uploadId,
            analysisRunId = UUID.randomUUID(),
            contentSha256 = "a".repeat(64),
            parserId = "docling",
            parserVersion = "1.30.0",
            metadataExtractionPolicyVersion = "docling-first-page-metadata-candidates-v1",
            identityPolicyVersion = "recovery-upload-identity-v2",
            parserOptions = mapOf("from_formats" to "pdf", "to_formats" to "md,json", "do_ocr" to "false"),
            languageDetectorId = "optimaize",
            languageDetectorVersion = "0.6",
            minimumLanguageConfidence = 0.65,
            validationStatus = RecoveryValidationStatus.COMPLETED,
            identityOutcome = RecoveryIdentityOutcome.NEEDS_CONFIRMATION,
            identityReasonCode = "DOI_DIFFERS_REQUIRES_CONFIRMATION",
            metadataCandidates = listOf(
                ParsedBibliographicMetadataCandidate(
                    field = ParsedBibliographicMetadataField.TITLE,
                    value = "Synthetic Candidate",
                    pageNumber = 1,
                    sourceLabel = "title",
                    extractionMethod = ParsedBibliographicMetadataExtractionMethod.DOCLING_LABEL,
                    sourceElementId = "docling-1",
                    sourceCharSpanStart = 0,
                    sourceCharSpanEnd = 18,
                ),
            ),
            languageEligibility = RecoveryLanguageEligibility.ELIGIBLE,
            detectedLanguage = "en",
            languageConfidence = 0.99,
            languageReasonCode = "ENGLISH_DETECTED",
            failureCode = null,
            createdAt = Instant.parse("2026-10-09T00:00:00Z"),
        )
        Mockito.`when`(recoveryUploadValidationService.validate(batchId, uploadId)).thenReturn(attempt)
        Mockito.`when`(recoveryUploadIdentityService.confirmation(batchId, uploadId, attempt)).thenReturn(null)
        Mockito.`when`(recoveryUploadIdentityService.selection(batchId, uploadId)).thenReturn(null)

        mockMvc.perform(post("/api/v1/recovery-batches/$batchId/uploads/$uploadId/validate"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.identityOutcome").value("NEEDS_CONFIRMATION"))
            .andExpect(jsonPath("$.identityReasonCode").value("DOI_DIFFERS_REQUIRES_CONFIRMATION"))
            .andExpect(jsonPath("$.identityPolicyVersion").value("recovery-upload-identity-v2"))
            .andExpect(jsonPath("$.languageEligibility").value("ELIGIBLE"))
            .andExpect(jsonPath("$.validationStatus").value("COMPLETED"))
            .andExpect(jsonPath("$.parserOptions.do_ocr").value("false"))
            .andExpect(jsonPath("$.metadataCandidates[0].sourceElementId").value("docling-1"))
            .andExpect(jsonPath("$.failureCode").doesNotExist())
    }

    @Test
    fun `identity confirmation and exact-version selection are distinct API actions`() {
        val batchId = UUID.randomUUID()
        val uploadId = UUID.randomUUID()
        val attemptId = UUID.randomUUID()
        val runId = UUID.randomUUID()
        val entryId = UUID.randomUUID()
        val hash = "c".repeat(64)
        val confirmedAt = Instant.parse("2026-10-09T00:01:00Z")
        val confirmation = RecoveryIdentityConfirmation(
            id = UUID.randomUUID(),
            batchId = batchId,
            uploadId = uploadId,
            validationAttemptId = attemptId,
            contentSha256 = hash,
            decision = "CONFIRM_EXACT_VERSION",
            confirmedAt = confirmedAt,
        )
        val selection = RecoveryUploadAssetSelection(
            id = UUID.randomUUID(),
            batchId = batchId,
            analysisRunId = runId,
            bibliographyEntryId = entryId,
            uploadId = uploadId,
            validationAttemptId = attemptId,
            contentSha256 = hash,
            selectionMethod = RecoveryAssetSelectionMethod.HUMAN_CONFIRMED,
            selectedAt = confirmedAt.plusSeconds(1),
        )
        Mockito.`when`(recoveryUploadIdentityService.confirmExactVersion(batchId, uploadId, attemptId, true)).thenReturn(confirmation)
        Mockito.`when`(recoveryUploadIdentityService.selectExactVersion(batchId, uploadId)).thenReturn(selection)

        mockMvc.perform(
            post("/api/v1/recovery-batches/$batchId/uploads/$uploadId/validation/$attemptId/confirm-identity")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"confirmExactVersion":true}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.decision").value("CONFIRM_EXACT_VERSION"))
            .andExpect(jsonPath("$.validationAttemptId").value(attemptId.toString()))
            .andExpect(jsonPath("$.contentSha256").value(hash))

        mockMvc.perform(post("/api/v1/recovery-batches/$batchId/uploads/$uploadId/select"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.selectionMethod").value("HUMAN_CONFIRMED"))
            .andExpect(jsonPath("$.bibliographyEntryId").value(entryId.toString()))
            .andExpect(jsonPath("$.contentSha256").value(hash))
    }

    @Test
    fun `Swagger UI is available from the API`() {
        mockMvc.perform(get("/swagger-ui/index.html"))
            .andExpect(status().isOk)
            .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
    }

    @Test
    fun `Scalar API reference is available and uses the generated OpenAPI document`() {
        val response = mockMvc.perform(get("/scalar"))
            .andExpect(status().isOk)
            .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
            .andReturn()
            .response
            .contentAsString

        assertTrue(response.contains("/v3/api-docs"))
        assertTrue(response.contains("Paper T-Rail API Reference"))
    }
}
