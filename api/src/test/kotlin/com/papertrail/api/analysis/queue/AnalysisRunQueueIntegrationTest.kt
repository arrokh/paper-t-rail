package com.papertrail.api.analysis.queue

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.papertrail.api.analysis.configuration.AggregationPolicySnapshot
import com.papertrail.api.analysis.configuration.AnalysisConfigurationSnapshot
import com.papertrail.api.analysis.execution.AnalysisRunExecutionRepository
import com.papertrail.api.analysis.execution.AnalysisRunExecutionService
import com.papertrail.api.analysis.execution.ExecutionCaptureSanitizer
import com.papertrail.api.analysis.execution.ExecutionOperationId
import com.papertrail.api.analysis.execution.ExecutionSpanArtifactSpec
import com.papertrail.api.analysis.execution.ExecutionSpanSpec
import com.papertrail.api.analysis.configuration.ExternalProviderConsentSnapshot
import com.papertrail.api.analysis.configuration.ProviderSelection
import com.papertrail.api.analysis.configuration.RunConfigurationFactory
import com.papertrail.api.analysis.configuration.ValidationLimitsSnapshot
import com.papertrail.api.analysis.http.CreatedAnalysisRunResponse
import com.papertrail.api.analysis.http.RunConfigurationRequest
import com.papertrail.api.analysis.service.AnalysisRunProcessingService
import com.papertrail.api.analysis.service.AnalysisRunService
import com.papertrail.api.analysis.service.AnalysisRunStageCompletionService
import com.papertrail.api.citation.claims.domain.AnalyzedAtomicClaim
import com.papertrail.api.citation.claims.domain.AtomicClaimCandidate
import com.papertrail.api.citation.claims.domain.ClaimAnalysisRequest
import com.papertrail.api.citation.claims.domain.ClaimAnalysisVersions
import com.papertrail.api.citation.claims.domain.CitationContextClaims
import com.papertrail.api.citation.claims.domain.CitationTargetKey
import com.papertrail.api.infrastructure.cache.RedisProviderCacheStore
import com.papertrail.api.infrastructure.messaging.events.PipelineEvent
import com.papertrail.api.scholarly.references.queue.REFERENCE_RESOLUTION_REQUESTED
import com.papertrail.api.scholarly.acquisition.queue.CITED_PAPER_ACQUISITION_REQUESTED
import com.papertrail.api.scholarly.acquisition.queue.CitedPaperAcquisitionRequestedHandler
import com.papertrail.api.scholarly.acquisition.queue.CitedPaperAcquisitionRequestedPayload
import com.papertrail.api.evidence.queue.CITED_PAPER_INDEXING_REQUESTED
import com.papertrail.api.evidence.queue.CitedPaperIndexingRequestedHandler
import com.papertrail.api.evidence.queue.CitedPaperIndexingRequestedPayload
import com.papertrail.api.evidence.queue.CitedPaperIndexingQueue
import com.papertrail.api.evidence.service.EvidenceRetrievalService
import com.papertrail.api.evidence.repository.EvidenceRetrievalRepository
import com.papertrail.api.evidence.repository.EvidenceReportRepository
import com.papertrail.api.evidence.report.EvidenceCoverageReportRepository
import com.papertrail.api.evidence.verification.domain.LayaEvidencePassageSpanPlanner
import com.papertrail.api.review.domain.HumanReviewAction
import com.papertrail.api.review.repository.HumanReviewRepository
import com.papertrail.api.review.repository.JdbcHumanReviewRepository
import com.papertrail.api.review.service.HumanReviewService
import com.papertrail.api.scholarly.acquisition.domain.TerminalVerificationStatus
import com.papertrail.api.evidence.verification.provider.JevSystemOneProviderException
import com.papertrail.api.evidence.verification.provider.JevSystemOneSettings
import com.papertrail.api.evidence.verification.provider.LayaSystemOneProviderException
import com.papertrail.api.evidence.verification.provider.LayaSystemOneSettings
import com.papertrail.api.evidence.verification.provider.MockSystemOneProvider
import com.papertrail.api.evidence.verification.provider.SystemOneProvider
import com.papertrail.api.evidence.verification.provider.SystemOneRequestPreflight
import com.papertrail.api.evidence.verification.domain.TestEvidenceAggregationThresholds
import com.papertrail.api.evidence.verification.domain.SemanticJudgementRequest
import com.papertrail.api.evidence.verification.domain.EvidencePassageForJudgement
import com.papertrail.api.evidence.verification.domain.SemanticJudgementResult
import com.papertrail.api.evidence.verification.domain.EvidenceJudgement
import com.papertrail.api.evidence.verification.domain.EvidenceJudgementKind
import com.papertrail.api.evidence.verification.domain.EvidenceRole
import com.papertrail.api.evidence.verification.repository.ClaimReferenceVerificationRepository
import com.papertrail.api.evidence.verification.repository.JdbcClaimReferenceVerificationRepository
import com.papertrail.api.evidence.verification.repository.EvidenceJudgementRepository
import com.papertrail.api.evidence.verification.repository.EvidencePassageSpanRepository
import com.papertrail.api.evidence.verification.service.EvidenceVerificationService
import com.papertrail.api.evidence.embedding.EmbeddingProvider
import com.papertrail.api.evidence.embedding.EmbeddingRequestContext
import com.papertrail.api.evidence.embedding.FeatureHashEmbeddingProvider
import com.papertrail.api.evidence.embedding.OllamaEmbeddingSettings
import com.papertrail.api.evidence.chunking.SectionAwareEvidenceChunker
import com.papertrail.api.evidence.retrieval.PostgresHybridEvidenceRetriever
import com.papertrail.api.evidence.retrieval.ReciprocalRankFusion
import com.papertrail.api.evidence.parsing.CitedPaperParser
import com.papertrail.api.evidence.parsing.DefaultCitedPaperParser
import com.papertrail.api.scholarly.acquisition.service.CitedPaperAccessService
import com.papertrail.api.scholarly.acquisition.repository.CitedPaperAccessRepository
import com.papertrail.api.scholarly.acquisition.client.OpenAccessProvider
import com.papertrail.api.scholarly.acquisition.client.OpenAccessProviderFactory
import com.papertrail.api.scholarly.acquisition.client.RedisUnpaywallDiscoveryCache
import com.papertrail.api.scholarly.acquisition.client.UnpaywallOpenAccessProviderFactory
import com.papertrail.api.scholarly.acquisition.client.RecordedFixtureOpenAccessProviderFactory
import com.papertrail.api.scholarly.acquisition.domain.AcquiredFullText
import com.papertrail.api.scholarly.acquisition.domain.OpenAccessDiscovery
import com.papertrail.api.scholarly.acquisition.domain.OpenAccessLocation
import com.papertrail.api.scholarly.acquisition.service.PdfBoxCitedPaperTextExtractor
import com.papertrail.api.scholarly.references.queue.ReferenceResolutionRequestedPayload
import com.papertrail.api.infrastructure.messaging.outbox.OutboxPublisher
import com.papertrail.api.infrastructure.messaging.redis.RedisStreamWorker
import com.papertrail.api.scholarly.references.queue.ReferenceResolutionRequestedHandler
import com.papertrail.api.document.validation.DocumentLanguageDetector
import com.papertrail.api.document.validation.LanguageDetection
import com.papertrail.api.document.validation.OptimaizeDocumentLanguageDetector
import com.papertrail.api.citation.claims.service.ClaimAnalysisService
import com.papertrail.api.citation.claims.service.ClaimAnalysisRequestFactory
import com.papertrail.api.citation.claims.provider.ClaimAnalysisProvider
import com.papertrail.api.citation.claims.provider.OpenAiCompatibleClaimAnalysisSettings
import com.papertrail.api.infrastructure.providers.openai.OpenAiCompatibleEndpointSettings
import com.papertrail.api.infrastructure.providers.openai.OpenAiCompatibleProviderException
import com.papertrail.api.infrastructure.providers.openai.RetryableOpenAiCompatibleProviderException
import com.papertrail.api.citation.claims.service.HeuristicClaimExtractor
import com.papertrail.api.citation.claims.service.HeuristicClaimAnalysisProvider
import com.papertrail.api.document.validation.PdfDocumentValidator
import com.papertrail.api.citation.parsing.ParsedBibliographyEntry
import com.papertrail.api.citation.parsing.ParsedCitationContext
import com.papertrail.api.citation.parsing.ParsedCitationOccurrence
import com.papertrail.api.citation.parsing.ParsedDocumentRepository
import com.papertrail.api.citation.parsing.ParsedScientificDocument
import com.papertrail.api.citation.parsing.ParsedSection
import com.papertrail.api.citation.parsing.ScientificDocumentParser
import com.papertrail.api.infrastructure.providers.DataCategory
import com.papertrail.api.infrastructure.providers.ProviderCatalog
import com.papertrail.api.infrastructure.providers.ProviderCallGate
import com.papertrail.api.scholarly.references.client.BibliographyReference
import com.papertrail.api.scholarly.references.client.CrossrefLookupCache
import com.papertrail.api.scholarly.references.client.CrossrefScholarlyMetadataLookup
import com.papertrail.api.scholarly.references.client.RedisCrossrefLookupCache
import com.papertrail.api.scholarly.references.client.ScholarlyMetadataLookup
import com.papertrail.api.scholarly.references.client.ScholarlyMetadataLookupFactory
import com.papertrail.api.scholarly.references.client.ScholarlyWork
import com.papertrail.api.scholarly.references.service.RecordedFixtureScholarlyMetadataLookupFactory
import com.papertrail.api.scholarly.references.normalization.DoiNormalizer
import com.papertrail.api.scholarly.references.repository.ReferenceResolutionRepository
import com.papertrail.api.scholarly.references.service.ReferenceResolutionService
import com.papertrail.api.infrastructure.providers.externalProviderConsent
import com.papertrail.api.infrastructure.providers.configuredExternalProviderCatalog
import com.papertrail.api.document.storage.SourceDocumentObjectStore
import com.papertrail.api.document.storage.SourceObjectMetadata
import com.papertrail.api.document.service.SourceDocumentDeletionService
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.slf4j.LoggerFactory
import org.springframework.data.redis.connection.RedisStandaloneConfiguration
import org.springframework.data.redis.connection.stream.Consumer
import org.springframework.data.redis.connection.stream.ReadOffset
import org.springframework.data.redis.connection.stream.StreamOffset
import org.springframework.data.redis.connection.stream.StreamReadOptions
import org.springframework.data.domain.Range
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.MediaType
import org.springframework.test.web.client.ExpectedCount
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.TransactionSystemException
import org.springframework.transaction.support.SimpleTransactionStatus
import org.springframework.transaction.support.TransactionTemplate
import org.mockito.Mockito
import org.hamcrest.Matchers.containsString
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@Testcontainers
class AnalysisRunQueueIntegrationTest {
    @Test
    fun `execution artifacts are sanitized run-local deduplicated removable and deleted with their document`() {
        val firstRun = createQueuedRun()
        val otherRun = createQueuedRun()
        val execution = executionService()
        val logicalOperationId = ExecutionOperationId.forEvent(firstRun.eventId, "reference-resolution")
        val firstSpan = execution.startSpan(
            firstRun.analysisRunId,
            ExecutionSpanSpec("references", "PROVIDER", "Resolve bibliography entry", attempt = 2, eventId = firstRun.eventId, operationId = logicalOperationId),
        )!!
        val secondSpan = execution.startSpan(
            firstRun.analysisRunId,
            ExecutionSpanSpec("references", "PROVIDER", "Resolve bibliography entry", attempt = 3, eventId = UUID.randomUUID(), operationId = logicalOperationId),
        )!!
        val attribution = ExecutionSpanArtifactSpec(
            role = "REQUEST",
            schemaVersion = "citation-metadata-v1",
            fields = mapOf(
                "title" to "Evidence-guided analysis",
                "doi" to "10.1234/example.1",
                "authors" to listOf("Ada Example"),
                "publicationYear" to 2024,
            ),
        )
        execution.capture(firstRun.analysisRunId, firstSpan.id, attribution)
        execution.capture(
            firstRun.analysisRunId,
            firstSpan.id,
            attribution.copy(role = "RESPONSE", fields = attribution.fields + ("title" to "Evidence synthesis study")),
        )
        execution.capture(
            firstRun.analysisRunId,
            firstSpan.id,
            ExecutionSpanArtifactSpec(
                role = "RESULT",
                schemaVersion = "run-stage-result-v1",
                fields = mapOf("status" to "SUCCEEDED", "itemCount" to 1, "completedCount" to 1, "failedCount" to 0),
            ),
        )
        execution.capture(firstRun.analysisRunId, secondSpan.id, attribution)
        execution.finishSpan(firstSpan, "SUCCEEDED")
        execution.finishSpan(secondSpan, "FAILED", "PROVIDER_FAILURE")
        val timelineStart = Instant.parse("2026-01-01T00:00:00Z")
        jdbc.update(
            "UPDATE analysis_run_execution SET started_at = ?, finished_at = ? WHERE analysis_run_id = ?",
            Timestamp.from(timelineStart),
            Timestamp.from(timelineStart.plusSeconds(12)),
            firstRun.analysisRunId,
        )
        jdbc.update(
            "UPDATE analysis_run_execution_spans SET started_at = ?, ended_at = ?, duration_millis = 10000 WHERE id = ?",
            Timestamp.from(timelineStart), Timestamp.from(timelineStart.plusSeconds(10)), firstSpan.id,
        )
        jdbc.update(
            "UPDATE analysis_run_execution_spans SET started_at = ?, ended_at = ?, duration_millis = 10000 WHERE id = ?",
            Timestamp.from(timelineStart.plusSeconds(2)), Timestamp.from(timelineStart.plusSeconds(12)), secondSpan.id,
        )
        assertEquals(12_000L, execution.summary(firstRun.analysisRunId).totalDurationMillis)

        val artifactId = jdbc.queryForObject(
            "SELECT DISTINCT artifact.id FROM analysis_run_execution_artifacts artifact JOIN analysis_run_execution_span_artifacts link ON link.analysis_run_id = artifact.analysis_run_id AND link.artifact_id = artifact.id WHERE artifact.analysis_run_id = ? AND link.role = 'REQUEST' LIMIT 1",
            UUID::class.java,
            firstRun.analysisRunId,
        )!!
        assertEquals(3, jdbc.queryForObject(
            "SELECT count(*) FROM analysis_run_execution_artifacts WHERE analysis_run_id = ?",
            Int::class.java,
            firstRun.analysisRunId,
        ))
        assertEquals(2, jdbc.queryForObject(
            "SELECT count(*) FROM analysis_run_execution_span_artifacts WHERE analysis_run_id = ? AND artifact_id = ?",
            Int::class.java,
            firstRun.analysisRunId,
            artifactId,
        ))
        val storedContent = jdbc.queryForObject(
            "SELECT content FROM analysis_run_execution_artifacts WHERE analysis_run_id = ? AND id = ?",
            String::class.java,
            firstRun.analysisRunId,
            artifactId,
        )!!
        assertTrue(storedContent.contains("Ada Example"))
        assertFalse(storedContent.contains("private@example.org"))
        val firstSpanDetail = execution.span(firstRun.analysisRunId, firstSpan.id)
        assertEquals(listOf("REQUEST", "RESPONSE", "RESULT"), firstSpanDetail.artifactRoles.map { it.role })
        assertTrue(firstSpanDetail.artifactRoles.all { it.fidelity == "SANITIZED" })
        assertEquals("FAILED", execution.span(firstRun.analysisRunId, secondSpan.id).status)
        assertEquals(logicalOperationId, execution.span(firstRun.analysisRunId, firstSpan.id).operationId)
        assertEquals(logicalOperationId, execution.span(firstRun.analysisRunId, secondSpan.id).operationId)
        assertThrows(ResponseStatusException::class.java) {
            execution.artifact(otherRun.analysisRunId, artifactId)
        }

        val stopped = execution.stopCapture(firstRun.analysisRunId)
        assertFalse(stopped.captureEnabled)
        assertEquals("STOPPED", stopped.recordingState)
        assertEquals(null, execution.startSpan(firstRun.analysisRunId, ExecutionSpanSpec("source", "INTERNAL", "Late operation")))
        execution.removeArtifact(firstRun.analysisRunId, artifactId)
        val metadataOnlyJdbc = Mockito.spy(jdbc)
        val metadataOnlyRepository = AnalysisRunExecutionRepository(
            metadataOnlyJdbc,
            TransactionTemplate(DataSourceTransactionManager(dataSource)),
            objectMapper,
        )
        val listedSpan = metadataOnlyRepository.page(firstRun.analysisRunId, 100, null).items.single { it.id == firstSpan.id }
        val detailedSpan = metadataOnlyRepository.span(firstRun.analysisRunId, firstSpan.id)!!
        assertEquals("REMOVED", listedSpan.artifactRoles.single { it.role == "REQUEST" }.fidelity)
        assertEquals("REMOVED", detailedSpan.artifactRoles.single { it.role == "REQUEST" }.fidelity)
        val descriptorQueries = Mockito.mockingDetails(metadataOnlyJdbc).invocations
            .filter { it.method.name == "query" }
            .mapNotNull { it.arguments.firstOrNull() as? String }
            .filter { it.contains("analysis_run_execution_span_artifacts") }
        assertTrue(descriptorQueries.size >= 2)
        assertTrue(descriptorQueries.none { it.contains("content", ignoreCase = true) })

        val removed = execution.artifact(firstRun.analysisRunId, artifactId)
        assertEquals("REMOVED", removed.fidelity)
        assertEquals(null, removed.content)
        execution.capture(firstRun.analysisRunId, firstSpan.id, attribution)
        assertEquals("REMOVED", execution.artifact(firstRun.analysisRunId, artifactId).fidelity)
        assertEquals(null, jdbc.queryForObject(
            "SELECT content FROM analysis_run_execution_artifacts WHERE analysis_run_id = ? AND id = ?",
            String::class.java,
            firstRun.analysisRunId,
            artifactId,
        ))
        assertEquals(2, jdbc.queryForObject(
            "SELECT count(*) FROM analysis_run_execution_span_artifacts WHERE analysis_run_id = ? AND artifact_id = ? AND fidelity = 'REMOVED'",
            Int::class.java,
            firstRun.analysisRunId,
            artifactId,
        ))

        sourceDocumentDeletionService().delete(firstRun.documentId)
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM analysis_run_execution WHERE analysis_run_id = ?", Int::class.java, firstRun.analysisRunId))
        assertEquals(null, execution.startSpan(firstRun.analysisRunId, ExecutionSpanSpec("source", "INTERNAL", "Late deleted-run operation")))
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM analysis_run_execution WHERE analysis_run_id = ?", Int::class.java, firstRun.analysisRunId))
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM analysis_run_execution WHERE analysis_run_id = ?", Int::class.java, otherRun.analysisRunId))
    }

    @Test
    fun `source parser capture stores only safe provenance and typed result while omitting raw request and response`() {
        val run = createQueuedRun()
        val event = jdbc.queryForObject(
            "SELECT payload::text FROM outbox_events WHERE event_id = ?",
            String::class.java,
            run.eventId,
        )!!

        eventHandler().handle(event)

        val parserSpanId = jdbc.queryForObject(
            "SELECT id FROM analysis_run_execution_spans WHERE analysis_run_id = ? AND name = 'Parse Source Document'",
            UUID::class.java,
            run.analysisRunId,
        )!!
        val detail = executionService().span(run.analysisRunId, parserSpanId)
        assertEquals("SUCCEEDED", detail.status)
        assertEquals(listOf("INPUT", "REQUEST", "RESPONSE", "RESULT"), detail.artifactRoles.map { it.role })
        assertEquals("SANITIZED", detail.artifactRoles.single { it.role == "INPUT" }.fidelity)
        assertEquals("OMITTED", detail.artifactRoles.single { it.role == "REQUEST" }.fidelity)
        assertEquals("UNSAFE_UNSTRUCTURED_CONTENT", detail.artifactRoles.single { it.role == "REQUEST" }.reason)
        assertEquals("OMITTED", detail.artifactRoles.single { it.role == "RESPONSE" }.fidelity)
        assertEquals("UNSAFE_UNSTRUCTURED_CONTENT", detail.artifactRoles.single { it.role == "RESPONSE" }.reason)
        assertEquals("SANITIZED", detail.artifactRoles.single { it.role == "RESULT" }.fidelity)

        val storedArtifacts = jdbc.queryForList(
            "SELECT content FROM analysis_run_execution_artifacts WHERE analysis_run_id = ?",
            String::class.java,
            run.analysisRunId,
        )
        assertTrue(storedArtifacts.size >= 2)
        assertTrue(storedArtifacts.none { it.contains("integration pdf bytes") || it.contains("test GROBID output") })
        assertTrue(storedArtifacts.any { it.contains("sourceSha256") })
        assertTrue(storedArtifacts.any { it.contains("candidateCount") })
    }

    @Test
    fun `capture opt-out keeps content artifacts out of storage while preserving operation timing`() {
        val configuration = objectMapper.writeValueAsString(
            configurationFactory().from(RunConfigurationRequest(captureExecution = false)),
        )
        val run = createQueuedRun(configurationJson = configuration)
        val event = jdbc.queryForObject(
            "SELECT payload::text FROM outbox_events WHERE event_id = ?",
            String::class.java,
            run.eventId,
        )!!

        eventHandler().handle(event)

        val summary = executionService().summary(run.analysisRunId)
        assertFalse(summary.captureEnabled)
        assertEquals("RECORDING", summary.recordingState)
        assertTrue(executionService().spans(run.analysisRunId, 100, null).items.isNotEmpty())
        assertEquals(0, jdbc.queryForObject(
            "SELECT count(*) FROM analysis_run_execution_artifacts WHERE analysis_run_id = ?",
            Int::class.java,
            run.analysisRunId,
        ))
        assertTrue(jdbc.queryForObject(
            "SELECT count(*) FROM analysis_run_execution_span_artifacts WHERE analysis_run_id = ? AND reason = 'CAPTURE_DISABLED'",
            Int::class.java,
            run.analysisRunId,
        )!! > 0)
    }

    @Test
    fun `PARSED is terminal for execution recording`() {
        val run = createQueuedRun()
        val execution = executionService()

        jdbc.update("UPDATE analysis_runs SET status = 'PROCESSING' WHERE id = ?", run.analysisRunId)
        jdbc.update("UPDATE analysis_runs SET status = 'PARSED' WHERE id = ?", run.analysisRunId)
        execution.finishIfTerminal(run.analysisRunId)

        val summary = execution.summary(run.analysisRunId)
        assertEquals("STOPPED", summary.recordingState)
        assertEquals("COMPLETE", summary.completeness)
        assertTrue(summary.finishedAt != null)
        assertEquals(null, execution.startSpan(run.analysisRunId, ExecutionSpanSpec("references", "INTERNAL", "Late operation")))
    }

    @Test
    fun `capture stopped before PARSED completion remains incomplete after recording finishes`() {
        val run = createQueuedRun()
        val execution = executionService()
        val stopped = execution.stopCapture(run.analysisRunId)
        assertEquals("RECORDING", stopped.completeness)
        assertEquals("STOPPED", stopped.recordingState)

        jdbc.update("UPDATE analysis_runs SET status = 'PROCESSING' WHERE id = ?", run.analysisRunId)
        jdbc.update("UPDATE analysis_runs SET status = 'PARSED' WHERE id = ?", run.analysisRunId)
        execution.finishIfTerminal(run.analysisRunId)

        val finished = execution.summary(run.analysisRunId)
        assertEquals("STOPPED", finished.recordingState)
        assertEquals("INCOMPLETE", finished.completeness)
        assertTrue(finished.finishedAt != null)
    }

    @Test
    fun `original source PDF links are available for a queued run and reject deleted or changed sources`() {
        val created = createQueuedRun()
        val source = analysisRunService().getSourcePdfAccess(created.analysisRunId)

        assertEquals("paper.pdf", source.filename)
        assertTrue(source.viewUrl.contains("source/${created.documentId}/${created.hash}.pdf"))
        assertTrue(source.downloadUrl.contains("source/${created.documentId}/${created.hash}.pdf"))
        assertTrue(source.expiresAt.isAfter(Instant.now()))

        val objectKey = "source/${created.documentId}/${created.hash}.pdf"
        objectStore.put(objectKey, "changed PDF bytes".toByteArray())
        val changed = assertThrows(ResponseStatusException::class.java) {
            analysisRunService().getSourcePdfAccess(created.analysisRunId)
        }
        assertEquals(HttpStatus.CONFLICT, changed.statusCode)
        objectStore.put(objectKey, "integration pdf bytes".toByteArray())

        jdbc.update("INSERT INTO source_document_tombstones (document_id) VALUES (?)", created.documentId)
        val deleted = assertThrows(ResponseStatusException::class.java) {
            analysisRunService().getSourcePdfAccess(created.analysisRunId)
        }
        assertEquals(HttpStatus.NOT_FOUND, deleted.statusCode)
    }

    @Test
    fun `document deletion removes scoped data and preserves assets referenced by an unrelated active run`() {
        val deleted = createQueuedRun()
        val documentEvent = jdbc.queryForObject(
            "SELECT payload::text FROM outbox_events WHERE event_id = ?",
            String::class.java,
            deleted.eventId,
        )!!
        eventHandler().handle(documentEvent)
        processReferenceResolutionEvents(deleted.analysisRunId)

        val verificationId = jdbc.queryForObject(
            "SELECT id FROM claim_paper_verifications WHERE analysis_run_id = ? AND processing_status = 'COMPLETED' LIMIT 1",
            UUID::class.java,
            deleted.analysisRunId,
        )!!
        humanReviewService().record(
            verificationId,
            HumanReviewAction.AGREE,
            null,
            "Separate human assessment to remove with this document.",
        )
        val canonicalPaperIds = jdbc.query(
            "SELECT DISTINCT canonical_paper_id FROM bibliography_entry_resolutions WHERE analysis_run_id = ? AND canonical_paper_id IS NOT NULL",
            { rs, _ -> rs.getObject(1, UUID::class.java) },
            deleted.analysisRunId,
        )
        val pendingReanalysis = createQueuedRunForExistingDocument(deleted.documentId, deleted.hash)
        val staleAnalysisEvent = jdbc.queryForObject(
            "SELECT payload::text FROM outbox_events WHERE analysis_run_id = ? AND event_type = ?",
            String::class.java,
            pendingReanalysis.analysisRunId,
            DOCUMENT_ANALYSIS_REQUESTED,
        )!!
        val staleResolutionEvent = jdbc.queryForObject(
            "SELECT payload::text FROM outbox_events WHERE analysis_run_id = ? AND event_type = ? LIMIT 1",
            String::class.java,
            deleted.analysisRunId,
            REFERENCE_RESOLUTION_REQUESTED,
        )!!

        val unrelated = createQueuedRun()
        val unrelatedDocumentEvent = jdbc.queryForObject(
            "SELECT payload::text FROM outbox_events WHERE event_id = ?",
            String::class.java,
            unrelated.eventId,
        )!!
        eventHandler().handle(unrelatedDocumentEvent)
        jdbc.query(
            "SELECT payload::text FROM outbox_events WHERE analysis_run_id = ? AND event_type = ? ORDER BY created_at, event_id",
            { rs, _ -> rs.getString(1) },
            unrelated.analysisRunId,
            REFERENCE_RESOLUTION_REQUESTED,
        ).forEach(referenceResolutionEventHandler()::handle)
        val unrelatedReferenceId = jdbc.queryForObject(
            "SELECT id FROM bibliography_entries WHERE analysis_run_id = ? AND local_reference_key = 'ref1'",
            UUID::class.java,
            unrelated.analysisRunId,
        )!!
        val sharedObjectKey = jdbc.queryForObject(
            "SELECT object_key FROM cited_paper_access WHERE analysis_run_id = ? AND object_key IS NOT NULL LIMIT 1",
            String::class.java,
            deleted.analysisRunId,
        )!!
        val sharedReferenceInserted = jdbc.update(
            """
            INSERT INTO cited_paper_access (
                asset_id, analysis_run_id, bibliography_entry_id, canonical_paper_id, access_status,
                access_reason, provider_id, metadata_available, abstract_available, source_url,
                license_identifier, location_version, location_host_type, discovered_at, object_key,
                content_sha256, content_media_type, language, language_detector_version
            )
            SELECT source.asset_id, ?, ?, source.canonical_paper_id, source.access_status,
                   source.access_reason, source.provider_id, source.metadata_available, source.abstract_available,
                   source.source_url, source.license_identifier, source.location_version, source.location_host_type,
                   source.discovered_at, source.object_key, source.content_sha256, source.content_media_type,
                   source.language, source.language_detector_version
              FROM cited_paper_access source
             WHERE source.analysis_run_id = ? AND source.object_key = ?
             LIMIT 1
            """.trimIndent(),
            unrelated.analysisRunId,
            unrelatedReferenceId,
            deleted.analysisRunId,
            sharedObjectKey,
        )
        assertEquals(1, sharedReferenceInserted)
        assertTrue(objectStore.contains(sharedObjectKey))

        val storedObjects = jdbc.query(
            """
            SELECT object_key FROM source_documents WHERE id = ?
            UNION
            SELECT parsed.raw_tei_object_key
              FROM parsed_document_parses parsed
              JOIN analysis_runs run ON run.id = parsed.analysis_run_id
             WHERE run.document_id = ?
            UNION
            SELECT access.object_key
              FROM cited_paper_access access
              JOIN analysis_runs run ON run.id = access.analysis_run_id
             WHERE run.document_id = ? AND access.object_key IS NOT NULL
            """.trimIndent(),
            { rs, _ -> rs.getString("object_key") },
            deleted.documentId,
            deleted.documentId,
            deleted.documentId,
        )
        assertTrue(storedObjects.size >= 3)
        assertTrue(jdbc.queryForObject("SELECT count(*) FROM human_reviews WHERE analysis_run_id = ?", Int::class.java, deleted.analysisRunId)!! > 0)
        assertTrue(jdbc.queryForObject("SELECT count(*) FROM inbox_events", Int::class.java)!! > 0)

        sourceDocumentDeletionService().delete(deleted.documentId)

        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM source_documents WHERE id = ?", Int::class.java, deleted.documentId))
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM analysis_runs WHERE document_id = ?", Int::class.java, deleted.documentId))
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE analysis_run_id IN (?, ?)", Int::class.java, deleted.analysisRunId, pendingReanalysis.analysisRunId))
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM parsed_document_parses WHERE analysis_run_id = ?", Int::class.java, deleted.analysisRunId))
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM atomic_claims WHERE analysis_run_id = ?", Int::class.java, deleted.analysisRunId))
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM bibliography_entry_resolutions WHERE analysis_run_id = ?", Int::class.java, deleted.analysisRunId))
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM cited_paper_access WHERE analysis_run_id = ?", Int::class.java, deleted.analysisRunId))
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM paper_chunks WHERE analysis_run_id = ?", Int::class.java, deleted.analysisRunId))
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM claim_paper_verifications WHERE analysis_run_id = ?", Int::class.java, deleted.analysisRunId))
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM human_reviews WHERE analysis_run_id = ?", Int::class.java, deleted.analysisRunId))
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM inbox_events WHERE analysis_run_id IN (?, ?)", Int::class.java, deleted.analysisRunId, pendingReanalysis.analysisRunId))
        canonicalPaperIds.forEach { canonicalPaperId ->
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM canonical_papers WHERE id = ?", Int::class.java, canonicalPaperId))
        }
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM source_document_tombstones WHERE document_id = ?", Int::class.java, deleted.documentId))
        storedObjects.filterNot { it == sharedObjectKey }.forEach { objectKey -> assertFalse(objectStore.contains(objectKey)) }
        assertTrue(objectStore.contains(sharedObjectKey))
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM analysis_runs WHERE id = ?", Int::class.java, unrelated.analysisRunId))
        assertTrue(objectStore.contains("source/${unrelated.documentId}/${unrelated.hash}.pdf"))
        assertNull(analysisRunService().get(deleted.analysisRunId))
        assertFalse(analysisRunService().list().items.any { it.documentId == deleted.documentId })

        assertEquals(
            UUID.fromString(objectMapper.readTree(staleAnalysisEvent).path("eventId").asText()),
            eventHandler().handle(staleAnalysisEvent),
        )
        assertEquals(
            UUID.fromString(objectMapper.readTree(staleResolutionEvent).path("eventId").asText()),
            referenceResolutionEventHandler().handle(staleResolutionEvent),
        )
        sourceDocumentDeletionService().delete(deleted.documentId)
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM source_document_tombstones WHERE document_id = ?", Int::class.java, deleted.documentId))
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM analysis_runs WHERE document_id = ?", Int::class.java, deleted.documentId))

        sourceDocumentDeletionService().delete(unrelated.documentId)
        assertFalse(objectStore.contains(sharedObjectKey))
        canonicalPaperIds.forEach { canonicalPaperId ->
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM canonical_papers WHERE id = ?", Int::class.java, canonicalPaperId))
        }
    }

    @Test
    fun `a tombstoned document cannot be reanalyzed or recreated by pending workers`() {
        val created = createQueuedRun()
        val queuedEvent = jdbc.queryForObject(
            "SELECT payload::text FROM outbox_events WHERE event_id = ?",
            String::class.java,
            created.eventId,
        )!!

        sourceDocumentDeletionService().delete(created.documentId)

        assertEquals(created.eventId, eventHandler().handle(queuedEvent))
        val now = Instant.now()
        val staleResolution = PipelineEvent(
            UUID.randomUUID(),
            REFERENCE_RESOLUTION_REQUESTED,
            1,
            created.analysisRunId,
            UUID.randomUUID(),
            null,
            now,
            0,
            ReferenceResolutionRequestedPayload(created.documentId, created.hash, UUID.randomUUID()),
        )
        val staleAcquisition = PipelineEvent(
            UUID.randomUUID(),
            CITED_PAPER_ACQUISITION_REQUESTED,
            1,
            created.analysisRunId,
            UUID.randomUUID(),
            null,
            now,
            0,
            CitedPaperAcquisitionRequestedPayload(created.documentId, created.hash, UUID.randomUUID()),
        )
        val staleIndexing = PipelineEvent(
            UUID.randomUUID(),
            CITED_PAPER_INDEXING_REQUESTED,
            1,
            created.analysisRunId,
            UUID.randomUUID(),
            null,
            now,
            0,
            CitedPaperIndexingRequestedPayload(created.documentId, created.hash, UUID.randomUUID()),
        )
        assertEquals(staleResolution.eventId, referenceResolutionEventHandler().handle(objectMapper.writeValueAsString(staleResolution)))
        assertEquals(staleAcquisition.eventId, citedPaperAccessEventHandler().handle(objectMapper.writeValueAsString(staleAcquisition)))
        assertEquals(staleIndexing.eventId, citedPaperIndexingEventHandler().handle(objectMapper.writeValueAsString(staleIndexing)))
        assertThrows(ResponseStatusException::class.java) {
            analysisRunService().createReanalysis(created.documentId, null)
        }
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM source_documents WHERE id = ?", Int::class.java, created.documentId))
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM analysis_runs WHERE document_id = ?", Int::class.java, created.documentId))
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE analysis_run_id = ?", Int::class.java, created.analysisRunId))
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM inbox_events", Int::class.java))
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM source_document_tombstones WHERE document_id = ?", Int::class.java, created.documentId))
        assertFalse(objectStore.contains("source/${created.documentId}/${created.hash}.pdf"))
    }

    @Test
    fun `failed object deletion keeps a retryable tombstone and retry completes cleanup`() {
        val created = createQueuedRun()
        val deleteAttempts = AtomicInteger()
        val flakyObjectStore = object : SourceDocumentObjectStore {
            override fun put(objectKey: String, content: ByteArray, contentType: String) = objectStore.put(objectKey, content, contentType)
            override fun get(objectKey: String): ByteArray = objectStore.get(objectKey)
            override fun stat(objectKey: String): SourceObjectMetadata = objectStore.stat(objectKey)
            override fun presignGet(objectKey: String, responseContentDisposition: String, expirySeconds: Int): String =
                objectStore.presignGet(objectKey, responseContentDisposition, expirySeconds)
            override fun delete(objectKey: String) {
                if (deleteAttempts.getAndIncrement() == 0) error("simulated object-store failure")
                objectStore.delete(objectKey)
            }
        }
        val deletionService = SourceDocumentDeletionService(
            jdbc,
            TransactionTemplate(DataSourceTransactionManager(dataSource)),
            flakyObjectStore,
        )

        assertThrows(ResponseStatusException::class.java) { deletionService.delete(created.documentId) }
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM source_document_tombstones WHERE document_id = ?", Int::class.java, created.documentId))
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM source_documents WHERE id = ?", Int::class.java, created.documentId))
        assertTrue(objectStore.contains("source/${created.documentId}/${created.hash}.pdf"))

        deletionService.delete(created.documentId)

        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM source_documents WHERE id = ?", Int::class.java, created.documentId))
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM analysis_runs WHERE document_id = ?", Int::class.java, created.documentId))
        assertFalse(objectStore.contains("source/${created.documentId}/${created.hash}.pdf"))
    }

    @Test
    fun `tombstoned document blocks run updates and inbox commits until cleanup is retried`() {
        val created = createQueuedRun()
        jdbc.update(
            "INSERT INTO source_document_tombstones (document_id) VALUES (?)",
            created.documentId,
        )

        assertThrows(DataAccessException::class.java) {
            jdbc.update(
                "UPDATE analysis_runs SET progress = '{\"stage\":\"FAILED\"}'::jsonb WHERE id = ?",
                created.analysisRunId,
            )
        }
        assertThrows(DataAccessException::class.java) {
            jdbc.update(
                "INSERT INTO inbox_events (event_id, analysis_run_id, handler_name) VALUES (?, ?, ?)",
                UUID.randomUUID(),
                created.analysisRunId,
                "stale-worker",
            )
        }

        sourceDocumentDeletionService().delete(created.documentId)

        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM analysis_runs WHERE id = ?", Int::class.java, created.analysisRunId))
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM inbox_events WHERE analysis_run_id = ?", Int::class.java, created.analysisRunId))
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM source_document_tombstones WHERE document_id = ?", Int::class.java, created.documentId))
    }

    @Test
    fun `upload creates queued run with pinned source hash configuration and transactional outbox`() {
        val pdf = englishPdf()
        val expectedHash = expectedSha256(pdf)
        val service = analysisRunService()
        val created = service.createFromUpload("paper.pdf", "application/pdf", pdf, RunConfigurationRequest())

        assertEquals("QUEUED", created.status)
        assertEquals(expectedHash, created.sourceContentSha256)
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM source_documents WHERE id = ?", Int::class.java, created.documentId))
        assertEquals("pdfbox", jdbc.queryForObject("SELECT parser_id FROM source_documents WHERE id = ?", String::class.java, created.documentId))
        assertEquals("grobid", jdbc.queryForObject("SELECT source_parser_id FROM analysis_runs WHERE id = ?", String::class.java, created.analysisRunId))
        assertEquals("0.9.1-crf", jdbc.queryForObject("SELECT source_parser_version FROM analysis_runs WHERE id = ?", String::class.java, created.analysisRunId))
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM analysis_runs WHERE id = ? AND status = 'QUEUED'", Int::class.java, created.analysisRunId))
        assertEquals(
            created.sourceContentSha256,
            jdbc.queryForObject("SELECT source_content_sha256 FROM analysis_runs WHERE id = ?", String::class.java, created.analysisRunId),
        )
        assertEquals(
            "heuristic",
            jdbc.queryForObject(
                "SELECT configuration_snapshot #>> '{claimExtractor,provider}' FROM analysis_runs WHERE id = ?",
                String::class.java,
                created.analysisRunId,
            ),
        )
        assertEquals(
            "LOCAL",
            jdbc.queryForObject(
                "SELECT configuration_snapshot #>> '{claimExtractor,trustBoundary}' FROM analysis_runs WHERE id = ?",
                String::class.java,
                created.analysisRunId,
            ),
        )
        assertEquals(
            "citation_context",
            jdbc.queryForObject(
                "SELECT configuration_snapshot #>> '{claimExtractor,dataCategories,0}' FROM analysis_runs WHERE id = ?",
                String::class.java,
                created.analysisRunId,
            ),
        )
        assertEquals(
            "PENDING",
            jdbc.queryForObject(
                "SELECT configuration_snapshot #>> '{referenceResolution,executionStatus}' FROM analysis_runs WHERE id = ?",
                String::class.java,
                created.analysisRunId,
            ),
        )
        assertEquals(
            "recorded-fixtures",
            jdbc.queryForObject(
                "SELECT configuration_snapshot #>> '{referenceResolution,provider,provider}' FROM analysis_runs WHERE id = ?",
                String::class.java,
                created.analysisRunId,
            ),
        )
        assertEquals(
            "title-author-year-weighted-edit-similarity-v1",
            jdbc.queryForObject(
                "SELECT configuration_snapshot #>> '{referenceResolution,scorePolicyVersion}' FROM analysis_runs WHERE id = ?",
                String::class.java,
                created.analysisRunId,
            ),
        )
        assertEquals(
            0.25,
            jdbc.queryForObject(
                "SELECT (configuration_snapshot #>> '{referenceResolution,confidenceThreshold}')::double precision FROM analysis_runs WHERE id = ?",
                Double::class.java,
                created.analysisRunId,
            ),
        )
        assertEquals(
            0,
            jdbc.queryForObject(
                "SELECT jsonb_array_length(configuration_snapshot -> 'externalProviderConsents') FROM analysis_runs WHERE id = ?",
                Int::class.java,
                created.analysisRunId,
            ),
        )
        assertEquals(
            100_000,
            jdbc.queryForObject(
                "SELECT (configuration_snapshot #>> '{validationLimits,maxExtractedCharactersPerPage}')::integer FROM analysis_runs WHERE id = ?",
                Int::class.java,
                created.analysisRunId,
            ),
        )
        assertEquals(
            5_000,
            jdbc.queryForObject(
                "SELECT (configuration_snapshot #>> '{validationLimits,maxClaimCitationPairs}')::integer FROM analysis_runs WHERE id = ?",
                Int::class.java,
                created.analysisRunId,
            ),
        )
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE analysis_run_id = ? AND published_at IS NULL", Int::class.java, created.analysisRunId))

        val secondRun = service.createReanalysis(created.documentId, null)
        assertNotEquals(created.analysisRunId, secondRun.analysisRunId)
        assertEquals(created.documentId, secondRun.documentId)
        assertEquals(created.sourceContentSha256, secondRun.sourceContentSha256)
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM analysis_runs WHERE document_id = ?", Int::class.java, created.documentId))
        assertThrows(DataAccessException::class.java) {
            jdbc.update("UPDATE analysis_runs SET configuration_snapshot = '{}'::jsonb WHERE id = ?", created.analysisRunId)
        }
        assertThrows(DataAccessException::class.java) {
            jdbc.update("UPDATE analysis_runs SET status = 'COMPLETED' WHERE id = ?", secondRun.analysisRunId)
        }
    }

    @Test
    fun `Crossref cache hit persists a resolution independently for each Analysis Run`() {
        val catalog = configuredExternalProviderCatalog()
        val configurationJson = objectMapper.writeValueAsString(
            configurationFactory(catalog).from(
                RunConfigurationRequest(
                    scholarlyMetadataProvider = "crossref",
                    externalProviderConsents = listOf(
                        externalProviderConsent(catalog, "crossref", listOf(DataCategory.BIBLIOGRAPHIC_METADATA.id)),
                    ),
                ),
            ),
        )
        val firstRun = createQueuedRun(configurationJson = configurationJson)
        val secondRun = createQueuedRun(configurationJson = configurationJson)
        listOf(firstRun, secondRun).forEach { run ->
            val documentEvent = jdbc.queryForObject(
                "SELECT payload::text FROM outbox_events WHERE analysis_run_id = ? AND event_type = ?",
                String::class.java,
                run.analysisRunId,
                DOCUMENT_ANALYSIS_REQUESTED,
            )!!
            eventHandler().handle(documentEvent)
        }
        val entryIds = listOf(firstRun, secondRun).map { run ->
            jdbc.queryForObject(
                "SELECT id FROM bibliography_entries WHERE analysis_run_id = ? AND local_reference_key = 'ref1'",
                UUID::class.java,
                run.analysisRunId,
            )!!
        }

        val builder = RestClient.builder().baseUrl("https://api.crossref.org")
        val server = MockRestServiceServer.bindTo(builder).build()
        server.expect(requestTo("https://api.crossref.org/works/10.5555/papertrail.fixture.reference-resolution.2024"))
            .andRespond(withSuccess(
                """{"message":{"DOI":"10.5555/papertrail.fixture.reference-resolution.2024","title":["A fixture study of conservative scholarly reference resolution"],"author":[{"name":"Riley Example"},{"name":"Jordan Researcher"}],"issued":{"date-parts":[[2024]]}}}""",
                MediaType.APPLICATION_JSON,
            ))
        val restClient = builder.build()
        val cache: CrossrefLookupCache = RedisCrossrefLookupCache(RedisProviderCacheStore(redis), objectMapper, Duration.ofDays(30), Duration.ofHours(1))
        val factory = object : ScholarlyMetadataLookupFactory {
            override val providerId = "crossref"

            override fun forRun(configuration: AnalysisConfigurationSnapshot): ScholarlyMetadataLookup = CrossrefScholarlyMetadataLookup(
                client = restClient,
                objectMapper = objectMapper,
                callGate = ProviderCallGate(catalog),
                configuration = configuration,
                contactEmail = null,
                cache = cache,
            )
        }
        val resolutionService = referenceResolutionService(listOf(factory))
        resolutionService.resolveEntry(firstRun.analysisRunId, entryIds[0])
        resolutionService.resolveEntry(secondRun.analysisRunId, entryIds[1])

        server.verify()
        listOf(firstRun, secondRun).forEach { run ->
            assertEquals(
                1,
                jdbc.queryForObject(
                    "SELECT count(*) FROM bibliography_entry_resolutions WHERE analysis_run_id = ? AND status = 'RESOLVED' AND provider_id = 'crossref'",
                    Int::class.java,
                    run.analysisRunId,
                ),
            )
        }
        assertEquals(2, jdbc.queryForObject(
            "SELECT count(*) FROM bibliography_entry_resolutions WHERE bibliography_entry_id IN (?, ?) AND status = 'RESOLVED'",
            Int::class.java,
            entryIds[0],
            entryIds[1],
        ))
        assertEquals(1, redis.keys("crossref:doi:*").size)
    }

    @Test
    fun `legacy issue 7 runs do not acquire cited full text without a pinned access provider`() {
        val created = createQueuedRun(configurationJson = issueSevenConfigurationJson())
        val documentEvent = jdbc.queryForObject(
            "SELECT payload::text FROM outbox_events WHERE analysis_run_id = ? AND event_type = ?",
            String::class.java,
            created.analysisRunId,
            DOCUMENT_ANALYSIS_REQUESTED,
        )!!
        eventHandler().handle(documentEvent)
        processReferenceResolutionEvents(created.analysisRunId)

        assertEquals(0, jdbc.queryForObject(
            "SELECT count(*) FROM outbox_events WHERE analysis_run_id = ? AND event_type = ?",
            Int::class.java,
            created.analysisRunId,
            CITED_PAPER_ACQUISITION_REQUESTED,
        ))
        assertEquals(0, jdbc.queryForObject(
            "SELECT count(*) FROM cited_paper_access WHERE analysis_run_id = ?",
            Int::class.java,
            created.analysisRunId,
        ))
        val entry = referenceResolutionService().report(created.analysisRunId)!!.referenceResolution.entries
            .single { it.localReferenceKey == "ref1" }
        assertEquals("RESOLVED", entry.status)
        assertNull(entry.citedPaperAccess)
        assertEquals("PARSED", jdbc.queryForObject("SELECT status FROM analysis_runs WHERE id = ?", String::class.java, created.analysisRunId))
        assertTrue(jdbc.queryForObject(
            "SELECT progress ->> 'message' FROM analysis_runs WHERE id = ?",
            String::class.java,
            created.analysisRunId,
        )!!.contains("semantic verification was not configured"))
    }

    @Test
    fun `legacy immutable runs without coverage configuration remain not run rather than inventing a policy`() {
        val created = createQueuedRun(configurationJson = legacyResolutionConfigurationJson())
        val event = jdbc.queryForObject("SELECT payload::text FROM outbox_events WHERE analysis_run_id = ?", String::class.java, created.analysisRunId)

        eventHandler().handle(event!!)

        assertEquals("PARSED", jdbc.queryForObject("SELECT status FROM analysis_runs WHERE id = ?", String::class.java, created.analysisRunId))
        assertEquals(5, jdbc.queryForObject(
            "SELECT count(*) FROM atomic_claim_citation_targets WHERE analysis_run_id = ?",
            Int::class.java,
            created.analysisRunId,
        ))
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM bibliography_entry_resolutions WHERE analysis_run_id = ?", Int::class.java, created.analysisRunId))
        val report = referenceResolutionService().report(created.analysisRunId)!!
        assertEquals("NOT_RUN", report.referenceResolution.executionStatus)
        assertEquals(null, report.referenceResolution.scorePolicyVersion)
        assertEquals(null, report.referenceResolution.confidenceThreshold)
        assertEquals(4, report.referenceResolution.summary.notAttempted)
        assertTrue(report.referenceResolution.entries.all { it.canonicalPaper == null })
    }

    @Test
    fun `persists only selected Citation Targets and creates no verification for an unlinked claim`() {
        val settings = OpenAiCompatibleClaimAnalysisSettings(
            endpoint = OpenAiCompatibleEndpointSettings(
                enabled = true,
                baseUrl = "http://127.0.0.1:9123/v1",
                trustedHosts = setOf("127.0.0.1"),
            ),
            modelId = "fixture-model",
        )
        val providerCatalog = ProviderCatalog.safeDefaults(openAiCompatibleClaimAnalysisSettings = settings)
        val configuration = configurationFactory(providerCatalog = providerCatalog).from(
            RunConfigurationRequest(claimExtractorProvider = OpenAiCompatibleClaimAnalysisSettings.PROVIDER_ID),
        )
        val created = createQueuedRun(configurationJson = objectMapper.writeValueAsString(configuration))
        val event = jdbc.queryForObject(
            "SELECT payload::text FROM outbox_events WHERE analysis_run_id = ? AND event_type = ?",
            String::class.java,
            created.analysisRunId,
            DOCUMENT_ANALYSIS_REQUESTED,
        )!!
        val selectiveProvider = object : ClaimAnalysisProvider {
            override val providerId = OpenAiCompatibleClaimAnalysisSettings.PROVIDER_ID
            override val version = OpenAiCompatibleClaimAnalysisSettings.VERSION
            override val modelId: String? = "fixture-model"
            override val targetSelectionPolicyVersion = ClaimAnalysisVersions.MODEL_TARGET_SELECTION_POLICY
            override val promptVersion = ClaimAnalysisVersions.OPENAI_COMPATIBLE_PROMPT
            override val outputMappingVersion = ClaimAnalysisVersions.OPENAI_COMPATIBLE_OUTPUT_MAPPING

            override fun analyze(
                request: ClaimAnalysisRequest,
                configuration: AnalysisConfigurationSnapshot,
            ): List<CitationContextClaims> = request.contexts.mapIndexed { index, context ->
                val claimText = context.contextText.substringBefore(" [")
                CitationContextClaims(
                    context.contextStartOffset,
                    context.contextEndOffset,
                    listOf(
                        AnalyzedAtomicClaim(
                            AtomicClaimCandidate(
                                claimText,
                                context.contextStartOffset,
                                context.contextStartOffset + claimText.length,
                            ),
                            if (index == 0) listOf(CitationTargetKey(0, "ref1")) else emptyList(),
                        ),
                    ),
                )
            }
        }

        eventHandler(
            claimAnalysisProviders = listOf(selectiveProvider),
            providerCatalog = providerCatalog,
        ).handle(event)

        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM atomic_claims WHERE analysis_run_id = ?", Int::class.java, created.analysisRunId))
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM atomic_claim_citation_targets WHERE analysis_run_id = ?", Int::class.java, created.analysisRunId))
        assertEquals(setOf("ref1"), jdbc.query(
            """
            SELECT entry.local_reference_key
              FROM atomic_claim_citation_targets link
              JOIN citation_targets target ON target.analysis_run_id = link.analysis_run_id AND target.id = link.citation_target_id
              JOIN bibliography_entries entry ON entry.analysis_run_id = target.analysis_run_id AND entry.id = target.bibliography_entry_id
             WHERE link.analysis_run_id = ?
            """.trimIndent(),
            { rs, _ -> rs.getString("local_reference_key") },
            created.analysisRunId,
        ).toSet())
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM claim_paper_verifications WHERE analysis_run_id = ?", Int::class.java, created.analysisRunId))
    }

    @Test
    fun `selected claim provider availability is checked before the source PDF is retrieved`() {
        val settings = OpenAiCompatibleClaimAnalysisSettings(
            endpoint = OpenAiCompatibleEndpointSettings(
                enabled = true,
                baseUrl = "http://127.0.0.1:9123",
                trustedHosts = setOf("127.0.0.1"),
            ),
            modelId = "microsoft/phi-4-mini-reasoning",
        )
        val providerCatalog = ProviderCatalog.safeDefaults(openAiCompatibleClaimAnalysisSettings = settings)
        val configuration = configurationFactory(providerCatalog).from(
            RunConfigurationRequest(claimExtractorProvider = OpenAiCompatibleClaimAnalysisSettings.PROVIDER_ID),
        )
        val created = createQueuedRun(configurationJson = objectMapper.writeValueAsString(configuration))
        val event = jdbc.queryForObject(
            "SELECT payload::text FROM outbox_events WHERE analysis_run_id = ? AND event_type = ?",
            String::class.java,
            created.analysisRunId,
            DOCUMENT_ANALYSIS_REQUESTED,
        )!!
        val unavailableProvider = object : ClaimAnalysisProvider {
            override val providerId = OpenAiCompatibleClaimAnalysisSettings.PROVIDER_ID
            override val version = OpenAiCompatibleClaimAnalysisSettings.VERSION
            override val modelId: String? = settings.modelId
            override val targetSelectionPolicyVersion = ClaimAnalysisVersions.MODEL_TARGET_SELECTION_POLICY
            override val promptVersion = ClaimAnalysisVersions.OPENAI_COMPATIBLE_PROMPT
            override val outputMappingVersion = ClaimAnalysisVersions.OPENAI_COMPATIBLE_OUTPUT_MAPPING

            override fun validateAvailability(configuration: AnalysisConfigurationSnapshot) {
                throw RetryableOpenAiCompatibleProviderException("The claim-analysis endpoint is unavailable.")
            }

            override fun analyze(
                request: ClaimAnalysisRequest,
                configuration: AnalysisConfigurationSnapshot,
            ): List<CitationContextClaims> = error("Analysis must not begin before the endpoint preflight succeeds.")
        }

        assertThrows(RetryableOpenAiCompatibleProviderException::class.java) {
            eventHandler(
                claimAnalysisProviders = listOf(unavailableProvider),
                providerCatalog = providerCatalog,
            ).handle(event)
        }

        assertEquals(0, objectStore.getCalls())
        assertEquals("QUEUED", jdbc.queryForObject("SELECT status FROM analysis_runs WHERE id = ?", String::class.java, created.analysisRunId))
        assertEquals(0, jdbc.queryForObject(
            "SELECT count(*) FROM parsed_document_parses WHERE analysis_run_id = ?",
            Int::class.java,
            created.analysisRunId,
        ))
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM inbox_events WHERE event_id = ?", Int::class.java, created.eventId))
    }

    @Test
    fun `over-limit claim-citation pairs fail the run before parsed output is persisted`() {
        val configuration = configurationFactory(maxClaimCitationPairs = 4)
            .from(RunConfigurationRequest())
        val created = createQueuedRun(configurationJson = objectMapper.writeValueAsString(configuration))
        val event = jdbc.queryForObject(
            "SELECT payload::text FROM outbox_events WHERE analysis_run_id = ? AND event_type = ?",
            String::class.java,
            created.analysisRunId,
            DOCUMENT_ANALYSIS_REQUESTED,
        )!!

        eventHandler().handle(event)

        assertEquals("FAILED", jdbc.queryForObject("SELECT status FROM analysis_runs WHERE id = ?", String::class.java, created.analysisRunId))
        val failureReason = jdbc.queryForObject("SELECT failure_reason FROM analysis_runs WHERE id = ?", String::class.java, created.analysisRunId)!!
        assertTrue(failureReason.contains("CLAIM_CITATION_PAIRS_TOO_MANY"))
        assertTrue(failureReason.contains("5 claim-citation pairs"))
        assertTrue(failureReason.contains("configured limit is 4"))
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM parsed_document_parses WHERE analysis_run_id = ?", Int::class.java, created.analysisRunId))
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM atomic_claims WHERE analysis_run_id = ?", Int::class.java, created.analysisRunId))
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM atomic_claim_citation_targets WHERE analysis_run_id = ?", Int::class.java, created.analysisRunId))
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM inbox_events WHERE event_id = ?", Int::class.java, created.eventId))
    }

    @Test
    fun `parsed document is withheld until processing reaches a ready terminal state`() {
        val configuration = configurationFactory(maxClaimCitationPairs = 5).from(RunConfigurationRequest())
        val created = createQueuedRun(configurationJson = objectMapper.writeValueAsString(configuration))
        val service = analysisRunService()

        val pending = assertThrows(ResponseStatusException::class.java) {
            service.getParsedDocument(created.analysisRunId)
        }
        assertEquals(HttpStatus.CONFLICT, pending.statusCode)

        val missing = assertThrows(ResponseStatusException::class.java) {
            service.getParsedDocument(UUID.randomUUID())
        }
        assertEquals(HttpStatus.NOT_FOUND, missing.statusCode)

        val event = jdbc.queryForObject(
            "SELECT payload::text FROM outbox_events WHERE analysis_run_id = ? AND event_type = ?",
            String::class.java,
            created.analysisRunId,
            DOCUMENT_ANALYSIS_REQUESTED,
        )
        eventHandler().handle(event!!)
        processReferenceResolutionEvents(created.analysisRunId)

        assertEquals(5, jdbc.queryForObject(
            "SELECT count(*) FROM atomic_claim_citation_targets WHERE analysis_run_id = ?",
            Int::class.java,
            created.analysisRunId,
        ))
        val parsed = service.getParsedDocument(created.analysisRunId)
        assertEquals("grobid", parsed.parser.provider)
        assertEquals(created.hash, parsed.sourceContentSha256)
        val reference = referenceResolutionService().report(created.analysisRunId)!!.referenceResolution.entries
            .single { it.localReferenceKey == "ref1" }
        val access = reference.citedPaperAccess!!
        assertEquals("FULL_TEXT_AVAILABLE", access.accessStatus)
        assertEquals("recorded-fixtures", access.providerId)
        assertEquals("en", access.language)
        assertTrue(access.sourceUrl!!.startsWith("fixture://"))
        assertEquals(64, access.contentSha256!!.length)
        assertTrue(reference.verificationOutcomes.isNotEmpty())
        assertEquals(reference.verificationOutcomes, access.verificationOutcomes)
        assertTrue(reference.verificationOutcomes.all {
            it.processingStatus == "COMPLETED" && it.finalStatus == "INSUFFICIENT_EVIDENCE" && it.verificationScope == "FULL_TEXT"
        })
        val judgements = reference.verificationOutcomes.flatMap { it.evidencePassages }.mapNotNull { it.evidenceJudgement }
        assertTrue(judgements.isNotEmpty())
        assertTrue(judgements.all { it.providerId == "mock" && it.judgement == "INSUFFICIENT" })
    }

    @Test
    fun `Human Reviews append to the exact completed Verification without changing machine results`() {
        val created = createQueuedRun()
        val documentEvent = jdbc.queryForObject(
            "SELECT payload::text FROM outbox_events WHERE analysis_run_id = ? AND event_type = ?",
            String::class.java,
            created.analysisRunId,
            DOCUMENT_ANALYSIS_REQUESTED,
        )!!
        eventHandler().handle(documentEvent)
        val reviewService = humanReviewService()
        val invalidAction = assertThrows(ResponseStatusException::class.java) {
            reviewService.record(UUID.randomUUID(), HumanReviewAction.AGREE, TerminalVerificationStatus.SUPPORTED, null)
        }
        assertEquals(HttpStatus.BAD_REQUEST, invalidAction.statusCode)
        val missing = assertThrows(ResponseStatusException::class.java) {
            reviewService.record(UUID.randomUUID(), HumanReviewAction.AGREE, null, null)
        }
        assertEquals(HttpStatus.NOT_FOUND, missing.statusCode)
        val pendingVerificationId = jdbc.queryForObject(
            "SELECT id FROM claim_paper_verifications WHERE analysis_run_id = ? LIMIT 1",
            UUID::class.java,
            created.analysisRunId,
        )!!
        val pendingConflict = assertThrows(ResponseStatusException::class.java) {
            reviewService.record(pendingVerificationId, HumanReviewAction.AGREE, null, null)
        }
        assertEquals(HttpStatus.CONFLICT, pendingConflict.statusCode)

        processReferenceResolutionEvents(created.analysisRunId)
        val resolutionService = referenceResolutionService()
        val before = resolutionService.report(created.analysisRunId)!!.referenceResolution.entries
            .single { it.localReferenceKey == "ref1" }.verificationOutcomes.first()
        val machineBefore = jdbc.queryForMap(
            "SELECT processing_status, final_status, evidence_conflict, aggregator_version FROM claim_paper_verifications WHERE id = ?",
            before.id,
        )
        val machineStatus = TerminalVerificationStatus.valueOf(before.finalStatus!!)
        val overrideStatus = if (machineStatus == TerminalVerificationStatus.SUPPORTED) {
            TerminalVerificationStatus.CONTRADICTED
        } else {
            TerminalVerificationStatus.SUPPORTED
        }
        val agreement = reviewService.record(
            verificationId = before.id,
            action = HumanReviewAction.AGREE,
            overrideStatus = null,
            note = "I agree with the completed machine result.",
        )
        val override = reviewService.record(
            verificationId = before.id,
            action = HumanReviewAction.OVERRIDE,
            overrideStatus = overrideStatus,
            note = "My separate assessment is recorded for comparison.",
        )
        val disagreementWithoutOptionalFields = reviewService.record(
            verificationId = before.id,
            action = HumanReviewAction.DISAGREE,
            overrideStatus = null,
            note = null,
        )
        val after = resolutionService.report(created.analysisRunId)!!.referenceResolution.entries
            .single { it.localReferenceKey == "ref1" }.verificationOutcomes.first()

        assertEquals(machineStatus.name, after.finalStatus)
        assertEquals(machineBefore, jdbc.queryForMap(
            "SELECT processing_status, final_status, evidence_conflict, aggregator_version FROM claim_paper_verifications WHERE id = ?",
            before.id,
        ))
        assertEquals(3, jdbc.queryForObject(
            "SELECT count(*) FROM human_reviews WHERE analysis_run_id = ? AND verification_id = ?",
            Int::class.java,
            created.analysisRunId,
            before.id,
        ))
        assertEquals(setOf(agreement.id, override.id, disagreementWithoutOptionalFields.id), after.humanReviews.map { it.id }.toSet())
        assertTrue(after.humanReviews.all { it.analysisRunId == created.analysisRunId && it.verificationId == before.id })
        assertEquals(overrideStatus, after.humanReviews.single { it.action == HumanReviewAction.OVERRIDE }.overrideStatus)
        assertEquals("I agree with the completed machine result.", after.humanReviews.single { it.action == HumanReviewAction.AGREE }.note)
        assertNull(after.humanReviews.single { it.action == HumanReviewAction.DISAGREE }.overrideStatus)
        assertNull(after.humanReviews.single { it.action == HumanReviewAction.DISAGREE }.note)
        assertThrows(DataAccessException::class.java) {
            jdbc.update("UPDATE human_reviews SET note = 'rewritten' WHERE id = ?", agreement.id)
        }
        assertThrows(DataAccessException::class.java) {
            jdbc.update("DELETE FROM human_reviews WHERE id = ?", agreement.id)
        }
        assertEquals(3, jdbc.queryForObject(
            "SELECT count(*) FROM human_reviews WHERE analysis_run_id = ? AND verification_id = ?",
            Int::class.java,
            created.analysisRunId,
            before.id,
        ))

        jdbc.update("DELETE FROM claim_paper_verifications WHERE id = ?", before.id)
        assertEquals(0, jdbc.queryForObject(
            "SELECT count(*) FROM human_reviews WHERE analysis_run_id = ? AND verification_id = ?",
            Int::class.java,
            created.analysisRunId,
            before.id,
        ))
    }

    @Test
    fun `mock semantic fixture markers exercise conflict aggregation through the pipeline`() {
        val created = createQueuedRun()
        val documentEvent = jdbc.queryForObject(
            "SELECT payload::text FROM outbox_events WHERE analysis_run_id = ? AND event_type = ?",
            String::class.java,
            created.analysisRunId,
            DOCUMENT_ANALYSIS_REQUESTED,
        )!!
        eventHandler().handle(documentEvent)
        val location = OpenAccessLocation(
            "fixture://controlled/conflicting-evidence",
            "CC0-1.0",
            "publishedVersion",
            "repository",
            "recorded-fixtures",
        )
        val fetchCalls = AtomicInteger()
        val fullText = """
            ${MockSystemOneProvider.DIRECT_SUPPORT_FIXTURE_MARKER} Prior results support and reproduce the method in the treatment group.

            ${MockSystemOneProvider.CONTRADICTION_FIXTURE_MARKER} Later results do not support or reproduce the method in the treatment group.
        """.trimIndent()
        val citedPaperParser = object : CitedPaperParser {
            override fun parse(content: ByteArray, mediaType: String, parserSelection: ProviderSelection): ParsedScientificDocument {
                val text = content.toString(Charsets.UTF_8)
                val separator = "\n\n"
                val firstEnd = text.indexOf(separator)
                val secondStart = firstEnd + separator.length
                return ParsedScientificDocument(
                    parserId = "fixture-cited-paper-parser",
                    parserVersion = "v7",
                    normalizedSourceText = text,
                    sections = listOf(
                        ParsedSection(0, "Results", text.substring(0, firstEnd), 0, firstEnd),
                        ParsedSection(1, "Discussion", text.substring(secondStart), secondStart, text.length),
                    ),
                    citationContexts = emptyList(),
                    bibliographyEntries = emptyList(),
                )
            }
        }

        processReferenceResolutionEvents(
            analysisRunId = created.analysisRunId,
            providerFactories = listOf(
                controlledOpenAccessFactory(
                    OpenAccessDiscovery(true, false, listOf(location), "recorded-fixtures", Instant.now()),
                    fullText,
                    fetchCalls,
                ),
            ),
            citedPaperParser = citedPaperParser,
        )

        val reference = referenceResolutionService().report(created.analysisRunId)!!.referenceResolution.entries
            .single { it.localReferenceKey == "ref1" }
        val outcomes = reference.verificationOutcomes
        assertEquals(1, fetchCalls.get())
        assertTrue(outcomes.isNotEmpty())
        assertTrue(outcomes.all { it.processingStatus == "COMPLETED" }, outcomes.toString())
        assertTrue(outcomes.all { it.finalStatus == "INSUFFICIENT_EVIDENCE" }, outcomes.toString())
        assertTrue(outcomes.all { it.evidenceConflict }, outcomes.toString())
        val judgements = outcomes.flatMap { it.evidencePassages }.mapNotNull { it.evidenceJudgement }
        assertTrue(judgements.any { it.judgement == "DIRECT_SUPPORT" })
        assertTrue(judgements.any { it.judgement == "CONTRADICTS" })
        assertTrue(judgements.all { it.providerId == "mock" && it.confidence == 0.96 })
    }

    @Test
    fun `persists available abstract when every legal full-text location fails`() {
        val created = createQueuedRun()
        val documentEvent = jdbc.queryForObject(
            "SELECT payload::text FROM outbox_events WHERE analysis_run_id = ? AND event_type = ?",
            String::class.java,
            created.analysisRunId,
            DOCUMENT_ANALYSIS_REQUESTED,
        )!!
        eventHandler().handle(documentEvent)
        val failedLocation = OpenAccessLocation(
            "fixture://controlled/unavailable",
            "CC0-1.0",
            "publishedVersion",
            "repository",
            "recorded-fixtures",
        )
        val fetchCalls = AtomicInteger()
        val factory = controlledOpenAccessFactory(
            OpenAccessDiscovery(true, true, listOf(failedLocation), "recorded-fixtures", Instant.now()),
            fullText = null,
            fetchCalls = fetchCalls,
        )

        processReferenceResolutionEvents(created.analysisRunId, listOf(factory))

        val access = referenceResolutionService().report(created.analysisRunId)!!.referenceResolution.entries
            .single { it.localReferenceKey == "ref1" }.citedPaperAccess!!
        assertEquals(1, fetchCalls.get())
        assertEquals("ABSTRACT_ONLY", access.accessStatus)
        assertEquals("FULL_TEXT_ACQUISITION_FAILED", access.accessReason)
        assertEquals(failedLocation.url, access.sourceUrl)
        assertEquals("CC0-1.0", access.license)
        val outcomes = referenceResolutionService().report(created.analysisRunId)!!.referenceResolution.entries
            .single { it.localReferenceKey == "ref1" }.verificationOutcomes
        assertTrue(outcomes.all { it.finalStatus == "INSUFFICIENT_EVIDENCE" })
    }

    @Test
    fun `tries the next legally permitted location when an earlier full-text location fails`() {
        val created = createQueuedRun()
        val documentEvent = jdbc.queryForObject(
            "SELECT payload::text FROM outbox_events WHERE analysis_run_id = ? AND event_type = ?",
            String::class.java,
            created.analysisRunId,
            DOCUMENT_ANALYSIS_REQUESTED,
        )!!
        eventHandler().handle(documentEvent)
        val locations = listOf(
            OpenAccessLocation("fixture://recorded/missing", "CC0-1.0", "publishedVersion", "repository", "recorded-fixtures"),
            OpenAccessLocation("fixture://recorded/available", "CC0-1.0", "publishedVersion", "repository", "recorded-fixtures"),
        )
        val fetchCalls = AtomicInteger()
        val factory = object : OpenAccessProviderFactory {
            override val providerId = "recorded-fixtures"

            override fun forRun(configuration: AnalysisConfigurationSnapshot): OpenAccessProvider = object : OpenAccessProvider {
                override fun discover(reference: BibliographyReference) =
                    OpenAccessDiscovery(true, false, locations, providerId, Instant.now())

                override fun fetch(location: OpenAccessLocation): AcquiredFullText {
                    fetchCalls.incrementAndGet()
                    check(location.url.endsWith("available")) { "The first recorded location is unavailable." }
                    return AcquiredFullText("English full-text evidence from the repository.".toByteArray(), "text/plain", location)
                }
            }
        }

        processReferenceResolutionEvents(created.analysisRunId, listOf(factory))

        val access = referenceResolutionService().report(created.analysisRunId)!!.referenceResolution.entries
            .single { it.localReferenceKey == "ref1" }.citedPaperAccess!!
        assertEquals(2, fetchCalls.get())
        assertEquals("FULL_TEXT_AVAILABLE", access.accessStatus)
        assertNull(access.accessReason)
        assertEquals("fixture://recorded/available", access.sourceUrl)
    }

    @Test
    fun `Unpaywall cache hits revalidate legal locations and persist access per Analysis Run`() {
        val contactEmail = "unpaywall-cache@example.invalid"
        val catalog = ProviderCatalog.safeDefaults(
            unpaywallEnabled = true,
            unpaywallRetentionDisclosure = "Retention and deletion details are unknown for this controlled-provider test.",
            unpaywallContactEmail = contactEmail,
        )
        val configuration = configurationFactory(catalog).from(
            RunConfigurationRequest(
                openAccessProvider = UnpaywallOpenAccessProviderFactory.UNPAYWALL_PROVIDER,
                externalProviderConsents = listOf(externalProviderConsent(
                    catalog,
                    UnpaywallOpenAccessProviderFactory.UNPAYWALL_PROVIDER,
                    listOf(
                        DataCategory.BIBLIOGRAPHIC_METADATA.id,
                        DataCategory.CITED_PAPER_LOCATION.id,
                        DataCategory.PROVIDER_CONTACT_EMAIL.id,
                    ),
                )),
            ),
        )
        val discoveryBuilder = RestClient.builder().baseUrl("https://api.unpaywall.org")
        val contentBuilder = RestClient.builder()
        val discoveryServer = MockRestServiceServer.bindTo(discoveryBuilder).build()
        val contentServer = MockRestServiceServer.bindTo(contentBuilder).build()
        val factory = UnpaywallOpenAccessProviderFactory(
            objectMapper = objectMapper,
            providerCallGate = ProviderCallGate(catalog),
            discoveryCache = RedisUnpaywallDiscoveryCache(
                RedisProviderCacheStore(redisTemplate),
                objectMapper,
                Duration.ofHours(24),
                Duration.ofHours(1),
            ),
            unpaywallClient = discoveryBuilder.build(),
            contentClient = contentBuilder.build(),
            contactEmail = contactEmail,
            maximumBytes = 1_000_000,
        )
        val runs = listOf(
            createQueuedRun(configurationJson = objectMapper.writeValueAsString(configuration)),
            createQueuedRun(configurationJson = objectMapper.writeValueAsString(configuration)),
        )
        discoveryServer.expect(ExpectedCount.once(), requestTo(containsString("https://api.unpaywall.org/v2/")))
            .andExpect(queryParam("email", contactEmail))
            .andRespond(withSuccess(
                """{"doi":"10.5555/papertrail.fixture.reference-resolution.2024","abstract":"Available summary","oa_locations":[{"url_for_pdf":"https://8.8.8.8/restricted.pdf","license":"all-rights-reserved","version":"publishedVersion","host_type":"repository"},{"url_for_pdf":"https://8.8.4.4/legal.txt","license":"cc-by","version":"publishedVersion","host_type":"repository"}]}""",
                MediaType.APPLICATION_JSON,
            ))
        contentServer.expect(ExpectedCount.twice(), requestTo("https://8.8.4.4/legal.txt"))
            .andRespond(withSuccess(ENGLISH.repeat(10), MediaType.TEXT_PLAIN))
        contentServer.expect(ExpectedCount.never(), requestTo("https://8.8.8.8/restricted.pdf"))

        runs.forEach { run ->
            val documentEvent = jdbc.queryForObject(
                "SELECT payload::text FROM outbox_events WHERE analysis_run_id = ? AND event_type = ?",
                String::class.java,
                run.analysisRunId,
                DOCUMENT_ANALYSIS_REQUESTED,
            )!!
            eventHandler().handle(documentEvent)
            processReferenceResolutionEvents(run.analysisRunId, providerFactories = listOf(factory))
        }

        discoveryServer.verify()
        contentServer.verify()
        val outcomes = jdbc.query(
            "SELECT analysis_run_id, provider_id, access_status, source_url, object_key, discovered_at FROM cited_paper_access ORDER BY analysis_run_id",
        ) { rs, _ ->
            listOf(
                rs.getObject("analysis_run_id", UUID::class.java).toString(),
                rs.getString("provider_id"),
                rs.getString("access_status"),
                rs.getString("source_url"),
                rs.getString("object_key"),
                rs.getTimestamp("discovered_at").toInstant().toString(),
            )
        }
        assertEquals(2, outcomes.size)
        assertEquals(runs.map { it.analysisRunId.toString() }.toSet(), outcomes.map { it[0] }.toSet())
        assertTrue(outcomes.all { it[1] == UnpaywallOpenAccessProviderFactory.UNPAYWALL_PROVIDER })
        assertTrue(outcomes.all { it[2] == "FULL_TEXT_AVAILABLE" })
        assertTrue(outcomes.all { it[3] == "https://8.8.4.4/legal.txt" })
        assertTrue(outcomes.all { it[4] != "null" && it[4].contains("analysis-runs/") })
        assertEquals(2, outcomes.map { it[4] }.toSet().size)
        assertEquals(1, outcomes.map { it[5] }.toSet().size)
        val cacheKey = "unpaywall:doi:v1:10.5555/papertrail.fixture.reference-resolution.2024"
        assertTrue(redisTemplate.hasKey(cacheKey))
        assertTrue(redisTemplate.expire(cacheKey, Duration.ofMillis(100)) == true)
        Thread.sleep(200)
        assertFalse(redisTemplate.hasKey(cacheKey))
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM cited_paper_access", Int::class.java))
        outcomes.map { it[4] }.forEach { objectKey ->
            assertTrue(objectStore.get(objectKey).isNotEmpty())
        }
    }

    @Test
    fun `abstract-only cited access is terminal insufficient evidence and never fetches full text`() {
        val created = createQueuedRun()
        val documentEvent = jdbc.queryForObject(
            "SELECT payload::text FROM outbox_events WHERE analysis_run_id = ? AND event_type = ?",
            String::class.java,
            created.analysisRunId,
            DOCUMENT_ANALYSIS_REQUESTED,
        )!!
        eventHandler().handle(documentEvent)
        val fetched = AtomicInteger()
        val factory = controlledOpenAccessFactory(
            discovery = OpenAccessDiscovery(true, true, emptyList(), "recorded-fixtures", Instant.now()),
            fullText = null,
            fetchCalls = fetched,
        )

        processReferenceResolutionEvents(created.analysisRunId, listOf(factory))

        val reference = referenceResolutionService().report(created.analysisRunId)!!.referenceResolution.entries
            .single { it.localReferenceKey == "ref1" }
        val access = reference.citedPaperAccess!!
        assertEquals("ABSTRACT_ONLY", access.accessStatus)
        assertEquals("ABSTRACT_ONLY", access.accessReason)
        assertEquals(0, fetched.get())
        assertTrue(reference.verificationOutcomes.isNotEmpty())
        assertTrue(reference.verificationOutcomes.all { it.finalStatus == "INSUFFICIENT_EVIDENCE" })
        assertTrue(reference.verificationOutcomes.all { it.verificationScope == "ABSTRACT_ONLY" })
        assertTrue(reference.verificationOutcomes.all { it.terminalReason == "ABSTRACT_ONLY" })
    }

    @Test
    fun `metadata-only access is reported separately from inaccessible verification`() {
        val created = createQueuedRun()
        val documentEvent = jdbc.queryForObject(
            "SELECT payload::text FROM outbox_events WHERE analysis_run_id = ? AND event_type = ?",
            String::class.java,
            created.analysisRunId,
            DOCUMENT_ANALYSIS_REQUESTED,
        )!!
        eventHandler().handle(documentEvent)
        val factory = controlledOpenAccessFactory(
            OpenAccessDiscovery(true, false, emptyList(), "recorded-fixtures", Instant.now()),
            fullText = null,
            fetchCalls = AtomicInteger(),
        )

        processReferenceResolutionEvents(created.analysisRunId, listOf(factory))

        val access = referenceResolutionService().report(created.analysisRunId)!!.referenceResolution.entries
            .single { it.localReferenceKey == "ref1" }.citedPaperAccess!!
        assertEquals("METADATA_ONLY", access.accessStatus)
        assertEquals("NO_LEGAL_FULL_TEXT_LOCATION", access.accessReason)
        val outcomes = referenceResolutionService().report(created.analysisRunId)!!.referenceResolution.entries
            .single { it.localReferenceKey == "ref1" }.verificationOutcomes
        assertTrue(outcomes.isNotEmpty())
        assertTrue(outcomes.all { it.finalStatus == "INACCESSIBLE" })
        assertTrue(outcomes.all { it.verificationScope == "NONE" })
    }

    @Test
    fun `missing full text and abstract is persisted as inaccessible independently of access status`() {
        val created = createQueuedRun()
        val documentEvent = jdbc.queryForObject(
            "SELECT payload::text FROM outbox_events WHERE analysis_run_id = ? AND event_type = ?",
            String::class.java,
            created.analysisRunId,
            DOCUMENT_ANALYSIS_REQUESTED,
        )!!
        eventHandler().handle(documentEvent)
        val factory = controlledOpenAccessFactory(null, null, AtomicInteger())

        processReferenceResolutionEvents(created.analysisRunId, listOf(factory))

        val reference = referenceResolutionService().report(created.analysisRunId)!!.referenceResolution.entries
            .single { it.localReferenceKey == "ref1" }
        val access = reference.citedPaperAccess!!
        assertEquals("UNAVAILABLE", access.accessStatus)
        assertEquals("NO_ACCESSIBLE_METADATA", access.accessReason)
        val outcomes = referenceResolutionService().report(created.analysisRunId)!!.referenceResolution.entries
            .single { it.localReferenceKey == "ref1" }.verificationOutcomes
        assertTrue(outcomes.isNotEmpty())
        assertTrue(outcomes.all { it.finalStatus == "INACCESSIBLE" })
        assertTrue(outcomes.all { it.verificationScope == "NONE" })
    }

    @Test
    fun `accessible non-English full text remains separate from terminal verification and is not semantically eligible`() {
        val created = createQueuedRun()
        val documentEvent = jdbc.queryForObject(
            "SELECT payload::text FROM outbox_events WHERE analysis_run_id = ? AND event_type = ?",
            String::class.java,
            created.analysisRunId,
            DOCUMENT_ANALYSIS_REQUESTED,
        )!!
        eventHandler().handle(documentEvent)
        val fetched = AtomicInteger()
        val location = OpenAccessLocation("fixture://controlled/non-english", "CC0-1.0", "publishedVersion", "repository", "recorded-fixtures")
        val factory = controlledOpenAccessFactory(
            OpenAccessDiscovery(true, false, listOf(location), "recorded-fixtures", Instant.now()),
            "Texte intégral de l’étude scientifique, résultats et analyse de recherche.".repeat(8),
            fetched,
        )
        val frenchDetector = object : DocumentLanguageDetector {
            override fun detect(text: String) = LanguageDetection("fr", 0.99)
        }

        processReferenceResolutionEvents(created.analysisRunId, listOf(factory), frenchDetector)

        val reference = referenceResolutionService().report(created.analysisRunId)!!.referenceResolution.entries
            .single { it.localReferenceKey == "ref1" }
        val access = reference.citedPaperAccess!!
        assertEquals("FULL_TEXT_AVAILABLE", access.accessStatus)
        assertEquals("fr", access.language)
        assertEquals(1, fetched.get())
        assertNull(access.evidenceIndexing)
        assertTrue(reference.verificationOutcomes.isNotEmpty())
        assertTrue(reference.verificationOutcomes.all { it.finalStatus == "INSUFFICIENT_EVIDENCE" })
        assertTrue(reference.verificationOutcomes.all { it.verificationScope == "NONE" })
        assertTrue(reference.verificationOutcomes.all { it.terminalReason == "LANGUAGE_UNSUPPORTED" })
    }

    @Test
    fun `runs selected Jev through shared judgement persistence and System One aggregation`() {
        val jevSettings = JevSystemOneSettings(apiKey = "server-side-test-key")
        val catalog = ProviderCatalog.safeDefaults(jevSystemOneSettings = jevSettings)
        val configuration = configurationFactory(
            providerCatalog = catalog,
            systemOneAggregationEnabled = true,
        ).from(
            RunConfigurationRequest(
                systemOneProvider = JevSystemOneSettings.PROVIDER_ID,
                externalProviderConsents = listOf(externalProviderConsent(
                    catalog,
                    JevSystemOneSettings.PROVIDER_ID,
                    listOf(DataCategory.ATOMIC_CLAIMS.id, DataCategory.EVIDENCE_PASSAGES.id),
                )),
            ),
        )
        val created = createQueuedRun(configurationJson = objectMapper.writeValueAsString(configuration))
        val jevCalls = AtomicInteger()
        val jevProvider = object : SystemOneProvider {
            override val providerId = JevSystemOneSettings.PROVIDER_ID
            override val version = JevSystemOneSettings.PROVIDER_VERSION
            override val modelId = JevSystemOneSettings.DEFAULT_MODEL_ID

            override fun evaluate(request: SemanticJudgementRequest): SemanticJudgementResult = SemanticJudgementResult(
                request.evidencePassages.map { passage ->
                    jevCalls.incrementAndGet()
                    EvidenceJudgement(
                        evidenceCandidateId = passage.id,
                        judgement = EvidenceJudgementKind.DIRECT_SUPPORT,
                        evidenceRole = EvidenceRole.PRIMARY_FINDING,
                        confidence = 0.9,
                        directness = 0.9,
                        claimScopeMatch = 0.9,
                        studyDesignQuality = 0.9,
                        relevance = 0.9,
                        providerReportedModelId = "jev-1.13.0",
                    )
                },
            )
        }
        val resolutionService = referenceResolutionService()
        val documentEvent = jdbc.queryForObject(
            "SELECT payload::text FROM outbox_events WHERE analysis_run_id = ? AND event_type = ?",
            String::class.java,
            created.analysisRunId,
            DOCUMENT_ANALYSIS_REQUESTED,
        )!!
        eventHandler(resolutionService = resolutionService).handle(documentEvent)

        processReferenceResolutionEvents(
            analysisRunId = created.analysisRunId,
            providerFactories = listOf(RecordedFixtureOpenAccessProviderFactory(
                objectMapper,
                ProviderCallGate(ProviderCatalog.safeDefaults()),
            )),
            resolutionService = resolutionService,
            systemOneProvider = jevProvider,
            providerCatalog = catalog,
        )

        val report = referenceResolutionService().report(created.analysisRunId)!!
        val outcomes = report.referenceResolution.entries.single { it.localReferenceKey == "ref1" }.verificationOutcomes
        assertTrue(jevCalls.get() > 0)
        assertEquals("COMPLETED", report.runStatus)
        assertEquals("COMPLETED", report.evidenceCoverage.executionStatus)
        assertTrue(outcomes.filter { it.verificationScope == "FULL_TEXT" }
            .all { it.processingStatus == "COMPLETED" && it.finalStatus == "SUPPORTED" })
        assertTrue(jdbc.queryForObject(
            "SELECT count(*) FROM evidence_judgements WHERE analysis_run_id = ? AND system_one_provider = 'jev' AND system_one_model = 'jev-1.13.0'",
            Long::class.java,
            created.analysisRunId,
        )!! > 0)
        assertTrue(jdbc.queryForObject(
            "SELECT progress ->> 'message' FROM analysis_runs WHERE id = ?",
            String::class.java,
            created.analysisRunId,
        )!!.contains("Jev aggregation completed"))
    }

    @Test
    fun `retries transient Jev failures through the indexing queue without marking verification failed`() {
        val jevSettings = JevSystemOneSettings(apiKey = "server-side-test-key")
        val catalog = ProviderCatalog.safeDefaults(jevSystemOneSettings = jevSettings)
        val configuration = configurationFactory(
            providerCatalog = catalog,
            systemOneAggregationEnabled = true,
        ).from(
            RunConfigurationRequest(
                systemOneProvider = JevSystemOneSettings.PROVIDER_ID,
                externalProviderConsents = listOf(externalProviderConsent(
                    catalog,
                    JevSystemOneSettings.PROVIDER_ID,
                    listOf(DataCategory.ATOMIC_CLAIMS.id, DataCategory.EVIDENCE_PASSAGES.id),
                )),
            ),
        )
        val created = createQueuedRun(configurationJson = objectMapper.writeValueAsString(configuration))
        val jevCalls = AtomicInteger()
        val recoveringJevProvider = object : SystemOneProvider {
            override val providerId = JevSystemOneSettings.PROVIDER_ID
            override val version = JevSystemOneSettings.PROVIDER_VERSION
            override val modelId = JevSystemOneSettings.DEFAULT_MODEL_ID

            override fun evaluate(request: SemanticJudgementRequest): SemanticJudgementResult {
                if (jevCalls.getAndIncrement() == 0) {
                    throw JevSystemOneProviderException(
                        message = "Temporary test provider failure.",
                        failureReasonCode = JevSystemOneProviderException.TIMEOUT,
                        retryable = true,
                    )
                }
                return SemanticJudgementResult(request.evidencePassages.map { passage ->
                    EvidenceJudgement(
                        evidenceCandidateId = passage.id,
                        judgement = EvidenceJudgementKind.DIRECT_SUPPORT,
                        evidenceRole = EvidenceRole.PRIMARY_FINDING,
                        confidence = 0.9,
                        directness = 0.9,
                        claimScopeMatch = 0.9,
                        studyDesignQuality = 0.9,
                        relevance = 0.9,
                        providerReportedModelId = "jev-1.13.0",
                    )
                })
            }
        }
        val resolutionService = referenceResolutionService()
        val documentEvent = jdbc.queryForObject(
            "SELECT payload::text FROM outbox_events WHERE analysis_run_id = ? AND event_type = ?",
            String::class.java,
            created.analysisRunId,
            DOCUMENT_ANALYSIS_REQUESTED,
        )!!
        eventHandler(resolutionService = resolutionService).handle(documentEvent)

        processReferenceResolutionEvents(
            analysisRunId = created.analysisRunId,
            providerFactories = listOf(RecordedFixtureOpenAccessProviderFactory(
                objectMapper,
                ProviderCallGate(ProviderCatalog.safeDefaults()),
            )),
            resolutionService = resolutionService,
            systemOneProvider = recoveringJevProvider,
            providerCatalog = catalog,
            retryIndexingOnce = true,
        )

        val report = referenceResolutionService().report(created.analysisRunId)!!
        val outcomes = report.referenceResolution.entries.single { it.localReferenceKey == "ref1" }.verificationOutcomes
        assertEquals(3, jevCalls.get())
        assertEquals("COMPLETED", report.runStatus)
        assertEquals("COMPLETED", report.evidenceCoverage.executionStatus)
        assertTrue(outcomes.filter { it.verificationScope == "FULL_TEXT" }
            .all { it.processingStatus == "COMPLETED" && it.finalStatus == "SUPPORTED" })
        assertTrue(jdbc.queryForObject(
            "SELECT count(*) FROM evidence_judgements WHERE analysis_run_id = ? AND system_one_provider = 'jev'",
            Long::class.java,
            created.analysisRunId,
        )!! > 0)
    }

    @Test
    fun `a selected Jev provider failure leaves verification incomplete without fallback`() {
        val jevSettings = JevSystemOneSettings(apiKey = "server-side-test-key")
        val catalog = ProviderCatalog.safeDefaults(jevSystemOneSettings = jevSettings)
        val configuration = configurationFactory(
            providerCatalog = catalog,
            systemOneAggregationEnabled = true,
        ).from(
            RunConfigurationRequest(
                systemOneProvider = JevSystemOneSettings.PROVIDER_ID,
                externalProviderConsents = listOf(externalProviderConsent(
                    catalog,
                    JevSystemOneSettings.PROVIDER_ID,
                    listOf(DataCategory.ATOMIC_CLAIMS.id, DataCategory.EVIDENCE_PASSAGES.id),
                )),
            ),
        )
        val created = createQueuedRun(configurationJson = objectMapper.writeValueAsString(configuration))
        val jevCalls = AtomicInteger()
        val failingJevProvider = object : SystemOneProvider {
            override val providerId = JevSystemOneSettings.PROVIDER_ID
            override val version = JevSystemOneSettings.PROVIDER_VERSION
            override val modelId = JevSystemOneSettings.DEFAULT_MODEL_ID

            override fun evaluate(request: SemanticJudgementRequest): SemanticJudgementResult {
                jevCalls.incrementAndGet()
                throw JevSystemOneProviderException(
                    "Jev System One returned an inconsistent weighted score.",
                    JevSystemOneProviderException.SCORE_INVALID,
                    "answers.directness.score",
                    JevSystemOneProviderException.SCORE_WEIGHTED_MEAN_MISMATCH,
                )
            }
        }
        val resolutionService = referenceResolutionService()
        val documentEvent = jdbc.queryForObject(
            "SELECT payload::text FROM outbox_events WHERE analysis_run_id = ? AND event_type = ?",
            String::class.java,
            created.analysisRunId,
            DOCUMENT_ANALYSIS_REQUESTED,
        )!!
        eventHandler(resolutionService = resolutionService).handle(documentEvent)

        val verificationLogger = LoggerFactory.getLogger(EvidenceVerificationService::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        verificationLogger.addAppender(appender)
        val stageLogger = LoggerFactory.getLogger(AnalysisRunStageCompletionService::class.java) as Logger
        val stageAppender = ListAppender<ILoggingEvent>().apply { start() }
        stageLogger.addAppender(stageAppender)
        try {
            processReferenceResolutionEvents(
                analysisRunId = created.analysisRunId,
                providerFactories = listOf(RecordedFixtureOpenAccessProviderFactory(
                    objectMapper,
                    ProviderCallGate(ProviderCatalog.safeDefaults()),
                )),
                resolutionService = resolutionService,
                systemOneProvider = failingJevProvider,
                providerCatalog = catalog,
            )
        } finally {
            verificationLogger.detachAppender(appender)
            appender.stop()
            stageLogger.detachAppender(stageAppender)
            stageAppender.stop()
        }

        val report = referenceResolutionService().report(created.analysisRunId)!!
        val outcomes = report.referenceResolution.entries.single { it.localReferenceKey == "ref1" }.verificationOutcomes
        assertTrue(jevCalls.get() > 0)
        val startedLog = appender.list.first { it.message == "System One verification started" }
        val startedFields = startedLog.keyValuePairs.associate { it.key to it.value.toString() }
        assertEquals(
            setOf("analysisRunId", "bibliographyEntryId", "verificationId", "providerId", "modelId", "atomicClaimId", "evidencePassageCount", "evidencePassageIds"),
            startedFields.keys,
        )
        assertEquals(created.analysisRunId.toString(), startedFields["analysisRunId"])
        assertEquals("jev", startedFields["providerId"])
        val failureLog = appender.list.first { it.message == "System One provider request failed" }
        val logFields = failureLog.keyValuePairs.associate { it.key to it.value.toString() }
        assertEquals(
            setOf("analysisRunId", "bibliographyEntryId", "verificationId", "providerId", "failureReasonCode", "exceptionType", "evidencePassageCount", "diagnosticField", "diagnosticReasonCode", "atomicClaimId", "evidencePassageIds"),
            logFields.keys,
        )
        assertEquals(created.analysisRunId.toString(), logFields["analysisRunId"])
        assertEquals("jev", logFields["providerId"])
        assertEquals(JevSystemOneProviderException.SCORE_INVALID, logFields["failureReasonCode"])
        assertEquals("answers.directness.score", logFields["diagnosticField"])
        assertEquals(
            JevSystemOneProviderException.SCORE_WEIGHTED_MEAN_MISMATCH,
            logFields["diagnosticReasonCode"],
        )
        assertFalse(failureLog.formattedMessage.contains("private claim"))
        assertFalse(failureLog.formattedMessage.contains("server-side-test-key"))
        val stageSummary = stageAppender.list.first { it.message == "Analysis Run processing stage completed" }
        val stageFields = stageSummary.keyValuePairs.associate { it.key to it.value.toString() }
        assertEquals(created.analysisRunId.toString(), stageFields["analysisRunId"])
        assertEquals("COMPLETED_WITH_WARNINGS", stageFields["runStatus"])
        assertEquals("jev", stageFields["systemOneProviderId"])
        assertEquals("0", stageFields["evidenceJudgementCount"])
        assertEquals("2", stageFields["failedEvaluationPairCount"])
        assertEquals("2", stageFields["failedSystemOneEvaluationCount"])
        assertTrue(stageFields.getValue("incompleteVerificationCount").toInt() > 0)
        assertFalse(stageSummary.formattedMessage.contains("private claim"))
        assertEquals("COMPLETED_WITH_WARNINGS", report.runStatus)
        assertEquals("COMPLETED_WITH_WARNINGS", report.evidenceCoverage.executionStatus)
        assertTrue(outcomes.filter { it.verificationScope == "FULL_TEXT" }.all {
            it.processingStatus == "INCOMPLETE" &&
                it.processingFailureReason == JevSystemOneProviderException.SCORE_INVALID &&
                it.finalStatus == null
        })
        assertEquals(0L, jdbc.queryForObject(
            "SELECT count(*) FROM evidence_judgements WHERE analysis_run_id = ?",
            Long::class.java,
            created.analysisRunId,
        ))
    }

    @Test
    fun `runs selected Jev for eligible passages without final aggregation when aggregation is disabled`() {
        val jevSettings = JevSystemOneSettings(apiKey = "server-side-test-key")
        val catalog = ProviderCatalog.safeDefaults(jevSystemOneSettings = jevSettings)
        val configuration = configurationFactory(
            providerCatalog = catalog,
            systemOneAggregationEnabled = false,
        ).from(
            RunConfigurationRequest(
                systemOneProvider = JevSystemOneSettings.PROVIDER_ID,
                externalProviderConsents = listOf(externalProviderConsent(
                    catalog,
                    JevSystemOneSettings.PROVIDER_ID,
                    listOf(DataCategory.ATOMIC_CLAIMS.id, DataCategory.EVIDENCE_PASSAGES.id),
                )),
            ),
        )
        val created = createQueuedRun(configurationJson = objectMapper.writeValueAsString(configuration))
        val jevCalls = AtomicInteger()
        val jevProvider = object : SystemOneProvider {
            override val providerId = JevSystemOneSettings.PROVIDER_ID
            override val version = JevSystemOneSettings.PROVIDER_VERSION
            override val modelId = JevSystemOneSettings.DEFAULT_MODEL_ID

            override fun evaluate(request: SemanticJudgementRequest): SemanticJudgementResult = SemanticJudgementResult(
                request.evidencePassages.map { passage ->
                    jevCalls.incrementAndGet()
                    EvidenceJudgement(
                        evidenceCandidateId = passage.id,
                        judgement = EvidenceJudgementKind.DIRECT_SUPPORT,
                        evidenceRole = EvidenceRole.PRIMARY_FINDING,
                        confidence = 0.9,
                        directness = 0.9,
                        claimScopeMatch = 0.9,
                        studyDesignQuality = 0.9,
                        relevance = 0.9,
                        providerReportedModelId = "jev-1.13.0",
                    )
                },
            )
        }
        val resolutionService = referenceResolutionService()
        val documentEvent = jdbc.queryForObject(
            "SELECT payload::text FROM outbox_events WHERE analysis_run_id = ? AND event_type = ?",
            String::class.java,
            created.analysisRunId,
            DOCUMENT_ANALYSIS_REQUESTED,
        )!!
        eventHandler(resolutionService = resolutionService).handle(documentEvent)

        val verificationLogger = LoggerFactory.getLogger(EvidenceVerificationService::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        verificationLogger.addAppender(appender)
        try {
            processReferenceResolutionEvents(
                analysisRunId = created.analysisRunId,
                providerFactories = listOf(RecordedFixtureOpenAccessProviderFactory(
                    objectMapper,
                    ProviderCallGate(ProviderCatalog.safeDefaults()),
                )),
                resolutionService = resolutionService,
                systemOneProvider = jevProvider,
                providerCatalog = catalog,
            )
        } finally {
            verificationLogger.detachAppender(appender)
            appender.stop()
        }

        val report = referenceResolutionService().report(created.analysisRunId)!!
        val outcomes = report.referenceResolution.entries.single { it.localReferenceKey == "ref1" }.verificationOutcomes
        assertTrue(jevCalls.get() > 0)
        val persistedLog = appender.list.first { it.message == "System One judgements persisted" }
        val persistedFields = persistedLog.keyValuePairs.associate { it.key to it.value.toString() }
        assertEquals(created.analysisRunId.toString(), persistedFields["analysisRunId"])
        assertEquals("jev", persistedFields["providerId"])
        assertEquals("JUDGEMENT_ONLY", persistedFields["evaluationMode"])
        assertEquals(persistedFields["evidencePassageCount"], persistedFields["judgementCount"])
        assertFalse(persistedLog.formattedMessage.contains("private claim"))
        assertEquals("PARSED", report.runStatus)
        assertEquals("NOT_RUN", report.evidenceCoverage.executionStatus)
        assertTrue(outcomes.filter { it.verificationScope == "FULL_TEXT" }
            .all { it.processingStatus == "PENDING" && it.finalStatus == null })
        assertTrue(jdbc.queryForObject(
            "SELECT progress ->> 'message' FROM analysis_runs WHERE id = ?",
            String::class.java,
            created.analysisRunId,
        )!!.contains("Jev produced"))
    }

    @Test
    fun `runs selected Laya for eligible passages when local aggregation is disabled`() {
        val (analysisRunId, layaCallCount) = runLayaPipeline(systemOneAggregationEnabled = false)
        val report = referenceResolutionService().report(analysisRunId)!!
        val reference = report.referenceResolution.entries.single { it.localReferenceKey == "ref1" }
        val passages = reference.verificationOutcomes.flatMap { it.evidencePassages }

        assertTrue(layaCallCount > 0)
        assertTrue(passages.any { it.evidenceJudgement?.providerId == LayaSystemOneSettings.PROVIDER_ID })
        assertEquals("PARSED", report.runStatus)
        assertEquals("NOT_RUN", report.evidenceCoverage.executionStatus)
        assertTrue(reference.verificationOutcomes.filter { it.verificationScope == "FULL_TEXT" }
            .all { it.finalStatus == null })
        assertTrue(jdbc.queryForObject(
            "SELECT progress ->> 'message' FROM analysis_runs WHERE id = ?",
            String::class.java,
            analysisRunId,
        )!!.contains("uncalibrated Evidence Judgement"))
        assertEquals(
            layaCallCount.toLong(),
            jdbc.queryForObject(
                "SELECT count(*) FROM evidence_judgements WHERE analysis_run_id = ? AND system_one_provider = ?",
                Long::class.java,
                analysisRunId,
                LayaSystemOneSettings.PROVIDER_ID,
            ),
        )
    }

    @Test
    fun `records a Laya context-limit rejection as an incomplete pair without retrying the request`() {
        val (analysisRunId, layaCallCount) = runLayaPipeline(
            systemOneAggregationEnabled = false,
            providerFailureReasonCode = LayaSystemOneProviderException.CONTEXT_LIMIT_EXCEEDED,
        )
        val report = referenceResolutionService().report(analysisRunId)!!
        val failedPairs = report.referenceResolution.entries.flatMap { it.verificationOutcomes }
            .filter { it.processingFailureReason == LayaSystemOneProviderException.CONTEXT_LIMIT_EXCEEDED }
        val progressMessage = jdbc.queryForObject(
            "SELECT progress ->> 'message' FROM analysis_runs WHERE id = ?",
            String::class.java,
            analysisRunId,
        )!!

        assertTrue(layaCallCount > 0)
        assertEquals(layaCallCount, failedPairs.size)
        assertEquals("COMPLETED_WITH_WARNINGS", report.runStatus)
        assertEquals("NOT_RUN", report.evidenceCoverage.executionStatus)
        assertTrue(progressMessage.contains("pair(s) unjudged"))
        assertTrue(progressMessage.contains("after System One assessment failed"))
        assertTrue(failedPairs.isNotEmpty())
        assertTrue(failedPairs.all { it.finalStatus == null })
        assertEquals(0L, jdbc.queryForObject(
            "SELECT count(*) FROM evidence_judgements WHERE analysis_run_id = ? AND system_one_provider = ?",
            Long::class.java,
            analysisRunId,
            LayaSystemOneSettings.PROVIDER_ID,
        ))
    }

    @Test
    fun `explicit local Laya aggregation persists final statuses and labels them experimental`() {
        val (analysisRunId, layaCallCount) = runLayaPipeline(systemOneAggregationEnabled = true)
        val report = referenceResolutionService().report(analysisRunId)!!
        val reference = report.referenceResolution.entries.single { it.localReferenceKey == "ref1" }
        val semanticOutcomes = reference.verificationOutcomes.filter { it.verificationScope == "FULL_TEXT" }

        assertTrue(layaCallCount > 0)
        assertEquals("COMPLETED", report.runStatus)
        assertEquals("COMPLETED", report.evidenceCoverage.executionStatus)
        assertEquals(0.8, report.evidenceCoverage.thresholds?.get("directSupport"))
        assertEquals(0.7, report.evidenceCoverage.thresholds?.get("partialSupport"))
        assertEquals(0.8, report.evidenceCoverage.thresholds?.get("contradiction"))
        assertEquals(0.08, report.evidenceCoverage.thresholds?.get("comparabilityMargin"))
        assertTrue(semanticOutcomes.isNotEmpty())
        assertTrue(semanticOutcomes.all { it.processingStatus == "COMPLETED" && it.finalStatus == "SUPPORTED" })
        assertTrue(jdbc.queryForObject(
            "SELECT progress ->> 'message' FROM analysis_runs WHERE id = ?",
            String::class.java,
            analysisRunId,
        )!!.contains("uncalibrated Evidence Judgement"))
        assertEquals(
            layaCallCount.toLong(),
            jdbc.queryForObject(
                "SELECT count(*) FROM evidence_judgements WHERE analysis_run_id = ? AND system_one_provider = ?",
                Long::class.java,
                analysisRunId,
                LayaSystemOneSettings.PROVIDER_ID,
            ),
        )
    }

    @Test
    fun `completed run with no eligible full text does not claim Laya aggregation ran`() {
        val (analysisRunId, layaCallCount) = runLayaPipeline(
            systemOneAggregationEnabled = true,
            metadataOnly = true,
        )
        val report = referenceResolutionService().report(analysisRunId)!!
        val outcomes = report.referenceResolution.entries.flatMap { it.verificationOutcomes }
        val accessOutcomes = report.referenceResolution.entries.mapNotNull { it.citedPaperAccess }
        val progressMessage = jdbc.queryForObject(
            "SELECT progress ->> 'message' FROM analysis_runs WHERE id = ?",
            String::class.java,
            analysisRunId,
        )!!
        val allEvidenceItemsSkipped = jdbc.queryForObject(
            "SELECT bool_and(status = 'SKIPPED') FROM analysis_run_pipeline_items WHERE analysis_run_id = ? AND stage_id = 'evidence'",
            Boolean::class.java,
            analysisRunId,
        )
        val allVerificationItemsSkipped = jdbc.queryForObject(
            "SELECT bool_and(status = 'SKIPPED') FROM analysis_run_pipeline_items WHERE analysis_run_id = ? AND stage_id = 'verification'",
            Boolean::class.java,
            analysisRunId,
        )

        assertEquals(0, layaCallCount)
        assertEquals("COMPLETED", report.runStatus)
        assertEquals("COMPLETED", report.evidenceCoverage.executionStatus)
        assertTrue(outcomes.isNotEmpty())
        assertTrue(outcomes.all { it.finalStatus != null })
        assertTrue(outcomes.all { it.evidencePassages.isEmpty() })
        assertTrue(accessOutcomes.any { it.accessStatus == "METADATA_ONLY" && it.accessReason == "NO_LEGAL_FULL_TEXT_LOCATION" })
        assertEquals(true, allEvidenceItemsSkipped)
        assertEquals(true, allVerificationItemsSkipped)
        assertTrue(progressMessage.contains("No eligible full-text evidence was available"))
        assertFalse(progressMessage.contains("Laya aggregation completed"))
    }

    private fun runLayaPipeline(
        systemOneAggregationEnabled: Boolean,
        providerFailureReasonCode: String? = null,
        tokenCountForPassage: (EvidencePassageForJudgement) -> Int = { 1 },
        judgementForResult: (Int) -> EvidenceJudgementKind = { EvidenceJudgementKind.DIRECT_SUPPORT },
        failOnceAtEvaluation: Int? = null,
        failOnceReasonCode: String? = null,
        retryIndexingOnce: Boolean = false,
        paperText: String? = null,
        metadataOnly: Boolean = false,
    ): Pair<UUID, Int> {
        val layaSettings = LayaSystemOneSettings(
            enabled = true,
            baseUrl = "http://127.0.0.1:8000",
            apiKey = "test-sidecar-key",
            trustedHosts = setOf("127.0.0.1"),
        )
        val layaCatalog = ProviderCatalog.safeDefaults(layaSystemOneSettings = layaSettings)
        val configured = configurationFactory(
            providerCatalog = layaCatalog,
            systemOneAggregationEnabled = systemOneAggregationEnabled,
        ).from(RunConfigurationRequest(systemOneProvider = LayaSystemOneSettings.PROVIDER_ID))
        val configuration = if (systemOneAggregationEnabled) configured else {
            configured.copy(aggregation = AggregationPolicySnapshot("NOT_RUN", null, null, null))
        }
        val created = createQueuedRun(configurationJson = objectMapper.writeValueAsString(configuration))
        val layaCalls = AtomicInteger()
        val evaluationAttempts = AtomicInteger()
        val successfulJudgements = AtomicInteger()
        val layaProvider = object : SystemOneProvider, SystemOneRequestPreflight {
            override val providerId = LayaSystemOneSettings.PROVIDER_ID
            override val version = LayaSystemOneSettings.PROVIDER_VERSION
            override val modelId = LayaSystemOneSettings.PINNED_MODEL_ID

            override fun tokenCounts(claim: String, passage: EvidencePassageForJudgement): List<Int> =
                List(6) { tokenCountForPassage(passage) }

            override fun evaluate(request: SemanticJudgementRequest): SemanticJudgementResult {
                val attempt = evaluationAttempts.incrementAndGet()
                if (providerFailureReasonCode != null) {
                    layaCalls.incrementAndGet()
                    throw LayaSystemOneProviderException("Test provider rejected the request.", providerFailureReasonCode)
                }
                if (attempt == failOnceAtEvaluation) {
                    layaCalls.incrementAndGet()
                    if (failOnceReasonCode != null) {
                        throw LayaSystemOneProviderException("Temporary test provider failure.", failOnceReasonCode)
                    }
                    throw IllegalStateException("Temporary test provider failure.")
                }
                return SemanticJudgementResult(request.evidencePassages.map { passage ->
                    layaCalls.incrementAndGet()
                    val resultIndex = successfulJudgements.incrementAndGet()
                    EvidenceJudgement(
                        evidenceCandidateId = passage.id,
                        judgement = judgementForResult(resultIndex),
                        evidenceRole = EvidenceRole.PRIMARY_FINDING,
                        confidence = 0.8,
                        directness = 0.8,
                        claimScopeMatch = 0.8,
                        studyDesignQuality = 0.8,
                        relevance = 0.8,
                    )
                })
            }
        }
        val resolutionService = referenceResolutionService()
        val documentEvent = jdbc.queryForObject(
            "SELECT payload::text FROM outbox_events WHERE analysis_run_id = ? AND event_type = ?",
            String::class.java,
            created.analysisRunId,
            DOCUMENT_ANALYSIS_REQUESTED,
        )!!
        eventHandler(resolutionService = resolutionService).handle(documentEvent)

        val providerFactories = when {
            metadataOnly -> listOf(
                controlledOpenAccessFactory(
                    OpenAccessDiscovery(true, false, emptyList(), "recorded-fixtures", Instant.now()),
                    fullText = null,
                    fetchCalls = AtomicInteger(),
                ),
            )
            paperText == null -> listOf(RecordedFixtureOpenAccessProviderFactory(objectMapper, ProviderCallGate(layaCatalog)))
            else -> listOf(
                controlledOpenAccessFactory(
                    OpenAccessDiscovery(
                        metadataAvailable = true,
                        abstractAvailable = false,
                        locations = listOf(OpenAccessLocation(
                            "fixture://controlled/laya-span-test",
                            "CC0-1.0",
                            "publishedVersion",
                            "repository",
                            "recorded-fixtures",
                        )),
                        providerId = "recorded-fixtures",
                        discoveredAt = Instant.now(),
                    ),
                    paperText,
                    AtomicInteger(),
                    onlyDoi = "10.5555/papertrail.fixture.reference-resolution.2024",
                ),
            )
        }
        processReferenceResolutionEvents(
            analysisRunId = created.analysisRunId,
            providerFactories = providerFactories,
            resolutionService = resolutionService,
            systemOneProvider = layaProvider,
            providerCatalog = layaCatalog,
            retryIndexingOnce = retryIndexingOnce,
        )
        return created.analysisRunId to layaCalls.get()
    }

    @Test
    fun `a complete Laya request at the exact tokenizer limit keeps the existing passage path`() {
        val text = "Prior results support the method. The study reports evidence in the treatment group. Later results do not support the method."
        val (analysisRunId, layaCallCount) = runLayaPipeline(
            systemOneAggregationEnabled = true,
            tokenCountForPassage = { LayaSystemOneSettings.MODEL_CONTEXT_TOKENS },
            paperText = text,
        )
        val outcomes = referenceResolutionService().report(analysisRunId)!!
            .referenceResolution.entries.single { it.localReferenceKey == "ref1" }.verificationOutcomes
        val passages = outcomes.flatMap { it.evidencePassages }

        assertTrue(passages.isNotEmpty())
        assertEquals(passages.size, layaCallCount)
        assertTrue(passages.all { it.diagnosticSpans.isEmpty() })
        assertTrue(passages.all { it.evidenceJudgement?.providerId == LayaSystemOneSettings.PROVIDER_ID })
        assertTrue(outcomes.all { it.finalStatus == "SUPPORTED" })
        assertEquals(0L, jdbc.queryForObject(
            "SELECT count(*) FROM laya_evidence_passage_spans WHERE analysis_run_id = ?",
            Long::class.java,
            analysisRunId,
        ))
    }

    @Test
    fun `an over-limit single sentence is persisted as incomplete without inference or truncation`() {
        val text = "Prior results support the method and reproduce it for the treatment group using carefully collected observations and independently validated outcomes."
        val (analysisRunId, layaCallCount) = runLayaPipeline(
            systemOneAggregationEnabled = false,
            tokenCountForPassage = { LayaSystemOneSettings.MODEL_CONTEXT_TOKENS + 1 },
            paperText = text,
        )
        val outcomes = referenceResolutionService().report(analysisRunId)!!
            .referenceResolution.entries.single { it.localReferenceKey == "ref1" }.verificationOutcomes
        val spans = outcomes.flatMap { it.evidencePassages }.flatMap { it.diagnosticSpans }

        assertEquals(0, layaCallCount)
        assertTrue(spans.isNotEmpty())
        assertTrue(spans.all { it.status == "INCOMPLETE" })
        assertTrue(spans.all { it.failureReason == "SINGLE_SENTENCE_EXCEEDS_CONTEXT_LIMIT" })
        assertTrue(spans.all { it.coreText == text && it.contextText == text })
        assertTrue(outcomes.all {
            it.processingStatus == "INCOMPLETE" &&
                it.processingFailureReason == "SYSTEM_ONE_INCOMPLETE" &&
                it.finalStatus == null
        })
        assertEquals("COMPLETED_WITH_WARNINGS", referenceResolutionService().report(analysisRunId)!!.runStatus)
        assertTrue(jdbc.queryForObject(
            "SELECT progress ->> 'message' FROM analysis_runs WHERE id = ?",
            String::class.java,
            analysisRunId,
        )!!.contains("required Laya span(s) are missing or incomplete"))
    }

    @Test
    fun `over-limit passage spans persist independently and retries skip successful span judgements`() {
        val text = "Prior results support the method. The study reports evidence in the treatment group. Later results do not support the method."
        val (analysisRunId, layaCallCount) = runLayaPipeline(
            systemOneAggregationEnabled = true,
            tokenCountForPassage = { passage -> 500 + passage.text.count { it == '.' } * 260 },
            judgementForResult = { index ->
                if (index % 2 == 1) EvidenceJudgementKind.DIRECT_SUPPORT else EvidenceJudgementKind.CONTRADICTS
            },
            failOnceAtEvaluation = 2,
            failOnceReasonCode = "TEST_RETRYABLE_FAILURE",
            retryIndexingOnce = true,
            paperText = text,
        )
        val report = referenceResolutionService().report(analysisRunId)!!
        val outcomes = report.referenceResolution.entries.single { it.localReferenceKey == "ref1" }.verificationOutcomes
        val diagnosticPassages = outcomes.flatMap { it.evidencePassages }.filter { it.diagnosticSpans.isNotEmpty() }
        val spans = diagnosticPassages.flatMap { it.diagnosticSpans }
        val lastSpanPerPassage = diagnosticPassages.map { it.diagnosticSpans.last() }

        assertTrue(diagnosticPassages.isNotEmpty())
        assertEquals(spans.size + 1, layaCallCount)
        assertTrue(diagnosticPassages.all { it.diagnosticSpans.size == 2 })
        assertTrue(spans.all { it.status == "COMPLETED" })
        assertEquals(setOf("DIRECT_SUPPORT", "CONTRADICTS"), spans.mapNotNull { it.evidenceJudgement?.judgement }.toSet())
        assertTrue(diagnosticPassages.all { it.evidenceJudgement == null })
        assertTrue(spans.all { it.tokenCounts.size == 6 && it.tokenCounts.all { count -> count <= 1024 } })
        assertTrue(lastSpanPerPassage.all { it.contextStartOffset < it.coreStartOffset })
        lastSpanPerPassage.forEach { span ->
            val parentText = diagnosticPassages.single { span in it.diagnosticSpans }.text
            assertEquals(parentText.substring(span.coreStartOffset, span.coreEndOffset), span.coreText)
            assertEquals(parentText.substring(span.contextStartOffset, span.contextEndOffset), span.contextText)
        }
        assertTrue(outcomes.all { it.processingStatus == "INCOMPLETE" && it.finalStatus == null })
        assertEquals(spans.size.toLong(), jdbc.queryForObject(
            "SELECT count(*) FROM laya_evidence_passage_spans WHERE analysis_run_id = ? AND status = 'COMPLETED'",
            Long::class.java,
            analysisRunId,
        ))
        diagnosticPassages.forEach { passage ->
            assertEquals(0L, jdbc.queryForObject(
                "SELECT count(*) FROM evidence_judgements WHERE evidence_candidate_id = ?",
                Long::class.java,
                passage.id,
            ))
        }
    }

    @Test
    fun `indexes eligible full text with Ollama while semantic aggregation remains disabled`() {
        val ollamaSettings = OllamaEmbeddingSettings(
            enabled = true,
            baseUrl = "http://ollama:11434",
            modelId = "nomic-embed-text",
            dimension = 768,
            trustedHosts = setOf("ollama"),
        )
        val providerCatalog = ProviderCatalog.safeDefaults(ollamaEmbeddingSettings = ollamaSettings)
        val configuration = configurationFactory(providerCatalog)
            .from(RunConfigurationRequest(embeddingProvider = OllamaEmbeddingSettings.PROVIDER_ID))
            .copy(aggregation = AggregationPolicySnapshot("NOT_RUN", null, null, null))
        val created = createQueuedRun(configurationJson = objectMapper.writeValueAsString(configuration))
        val embeddingCategories = mutableListOf<DataCategory>()
        val ollamaProvider = object : EmbeddingProvider {
            override val providerId = OllamaEmbeddingSettings.PROVIDER_ID
            override val modelId = "nomic-embed-text"
            override val version = OllamaEmbeddingSettings.VERSION
            override val dimension = 768

            override fun embed(text: String, context: EmbeddingRequestContext): FloatArray {
                embeddingCategories += context.inputCategory
                return FloatArray(dimension).apply { this[0] = 1.0f }
            }
        }
        val resolutionService = referenceResolutionService()
        val documentEvent = jdbc.queryForObject(
            "SELECT payload::text FROM outbox_events WHERE analysis_run_id = ? AND event_type = ?",
            String::class.java,
            created.analysisRunId,
            DOCUMENT_ANALYSIS_REQUESTED,
        )!!
        eventHandler(resolutionService = resolutionService).handle(documentEvent)

        processReferenceResolutionEvents(
            analysisRunId = created.analysisRunId,
            resolutionService = resolutionService,
            embeddingProviders = listOf(ollamaProvider),
        )

        val report = resolutionService.report(created.analysisRunId)!!
        val reference = report.referenceResolution.entries.single { it.localReferenceKey == "ref1" }
        val retrievalProfile = requireNotNull(reference.citedPaperAccess?.evidenceIndexing).retrievalProfile
        assertEquals("PARSED", report.runStatus)
        assertEquals("NOT_RUN", report.evidenceCoverage.executionStatus)
        assertEquals("COMPLETED", reference.citedPaperAccess?.evidenceIndexing?.status)
        assertEquals("ollama", retrievalProfile.embeddingProvider)
        assertEquals("nomic-embed-text", retrievalProfile.embeddingModel)
        assertEquals(768, retrievalProfile.embeddingDimension)
        assertTrue(embeddingCategories.contains(DataCategory.CITED_PAPER_CHUNKS))
        assertTrue(embeddingCategories.contains(DataCategory.ATOMIC_CLAIMS))
        assertTrue(reference.verificationOutcomes.flatMap { it.evidencePassages }.isNotEmpty())
        assertTrue(reference.verificationOutcomes.flatMap { it.evidencePassages }.all { it.evidenceJudgement == null })
        assertEquals(
            0L,
            jdbc.queryForObject(
                "SELECT count(*) FROM evidence_judgements WHERE analysis_run_id = ?",
                Long::class.java,
                created.analysisRunId,
            ),
        )
        assertTrue(
            jdbc.queryForObject(
                "SELECT count(*) FROM paper_chunk_embeddings WHERE analysis_run_id = ? AND provider_id = 'ollama' AND model_id = 'nomic-embed-text' AND dimension = 768",
                Long::class.java,
                created.analysisRunId,
            )!! > 0,
        )
    }

    @Test
    fun `retrieves ranked passages from each exact cited paper for only its linked atomic claim`() {
        val created = createQueuedRun()
        val claimOne = "The first intervention improves response"
        val claimTwo = "The second reagent increases yield"
        val firstContextText = "$claimOne [1]."
        val secondContextText = "$claimTwo [2]."
        val sourceText = "$firstContextText\n$secondContextText"
        val firstMarker = sourceText.indexOf("[1]")
        val secondClaimStart = sourceText.indexOf(claimTwo)
        val secondMarker = sourceText.indexOf("[2]")
        val sourceParser = object : ScientificDocumentParser {
            override fun parse(pdf: ByteArray) = ParsedScientificDocument(
                parserId = "grobid",
                parserVersion = "0.9.1-crf",
                normalizedSourceText = sourceText,
                sections = listOf(ParsedSection(0, "Results", sourceText, 0, sourceText.length)),
                citationContexts = listOf(
                    ParsedCitationContext(
                        0,
                        "CLAUSE",
                        firstContextText,
                        0,
                        firstContextText.length,
                        listOf(ParsedCitationOccurrence("[1]", firstMarker, firstMarker + 3, listOf("ref-alpha"))),
                    ),
                    ParsedCitationContext(
                        0,
                        "CLAUSE",
                        secondContextText,
                        secondClaimStart,
                        secondClaimStart + secondContextText.length,
                        listOf(ParsedCitationOccurrence("[2]", secondMarker, secondMarker + 3, listOf("ref-beta"))),
                    ),
                ),
                bibliographyEntries = listOf(
                    ParsedBibliographyEntry(0, "ref-alpha", "Alpha Study of Response", "Alpha Study of Response", listOf("Author Alpha"), 2020, "10.5555/papertrail.fixture.alpha.2020", "JOURNAL_ARTICLE"),
                    ParsedBibliographyEntry(1, "ref-beta", "Beta Study of Yield", "Beta Study of Yield", listOf("Author Beta"), 2021, "10.5555/papertrail.fixture.beta.2021", "JOURNAL_ARTICLE"),
                ),
                rawParserOutput = "<TEI>fixture source</TEI>".toByteArray(),
            )
        }
        val scholarlyWorks = listOf(
            ScholarlyWork("10.5555/papertrail.fixture.alpha.2020", "Alpha Study of Response", listOf("Author Alpha"), 2020),
            ScholarlyWork("10.5555/papertrail.fixture.beta.2021", "Beta Study of Yield", listOf("Author Beta"), 2021),
        )
        val lookupFactory = object : ScholarlyMetadataLookupFactory {
            override val providerId = "recorded-fixtures"
            override fun forRun(configuration: AnalysisConfigurationSnapshot) = object : ScholarlyMetadataLookup {
                override fun byDoi(doi: String) = scholarlyWorks.singleOrNull { it.doi == doi }
                override fun search(reference: BibliographyReference) = scholarlyWorks.filter { it.title == reference.title }
            }
        }
        val resolutionService = referenceResolutionService(listOf(lookupFactory))
        val paperTexts = mapOf(
            "fixture://cited/alpha" to "The first intervention improves response in the alpha cohort. Alpha-only marker: 4c2a.",
            "fixture://cited/beta" to "The second reagent increases yield in the beta cohort. Beta-only marker: 9d7f.",
        )
        val openAccessFactory = object : OpenAccessProviderFactory {
            override val providerId = "recorded-fixtures"
            override fun forRun(configuration: AnalysisConfigurationSnapshot) = object : OpenAccessProvider {
                override fun discover(reference: BibliographyReference): OpenAccessDiscovery? {
                    val key = if (reference.title == "Alpha Study of Response") "alpha" else "beta"
                    val location = OpenAccessLocation("fixture://cited/$key", "CC0-1.0", "publishedVersion", "repository", providerId)
                    return OpenAccessDiscovery(true, false, listOf(location), providerId, Instant.now())
                }

                override fun fetch(location: OpenAccessLocation) = AcquiredFullText(
                    bytes = requireNotNull(paperTexts[location.url]).toByteArray(Charsets.UTF_8),
                    mediaType = "text/plain",
                    location = location,
                )
            }
        }
        val citedPaperParser = object : CitedPaperParser {
            override fun parse(content: ByteArray, mediaType: String, parserSelection: ProviderSelection): ParsedScientificDocument {
                val text = content.toString(Charsets.UTF_8)
                return ParsedScientificDocument(
                    parserId = "fixture-cited-paper-parser",
                    parserVersion = "v7",
                    normalizedSourceText = text,
                    sections = listOf(ParsedSection(0, "Results", text, 0, text.length)),
                    citationContexts = emptyList(),
                    bibliographyEntries = emptyList(),
                )
            }
        }

        val documentEvent = jdbc.queryForObject(
            "SELECT payload::text FROM outbox_events WHERE analysis_run_id = ? AND event_type = ?",
            String::class.java,
            created.analysisRunId,
            DOCUMENT_ANALYSIS_REQUESTED,
        )!!
        eventHandler(parser = sourceParser, resolutionService = resolutionService).handle(documentEvent)
        processReferenceResolutionEvents(
            analysisRunId = created.analysisRunId,
            providerFactories = listOf(openAccessFactory),
            citedPaperParser = citedPaperParser,
            resolutionService = resolutionService,
        )

        val entries = resolutionService.report(created.analysisRunId)!!.referenceResolution.entries.associateBy { it.localReferenceKey }
        val alpha = entries.getValue("ref-alpha")
        val beta = entries.getValue("ref-beta")
        assertEquals("COMPLETED", alpha.citedPaperAccess?.evidenceIndexing?.status)
        assertEquals("COMPLETED", beta.citedPaperAccess?.evidenceIndexing?.status)
        assertEquals(claimOne, alpha.verificationOutcomes.single().claimText)
        assertEquals(claimTwo, beta.verificationOutcomes.single().claimText)
        val alphaPassage = alpha.verificationOutcomes.single().evidencePassages.single()
        val betaPassage = beta.verificationOutcomes.single().evidencePassages.single()
        assertTrue(alphaPassage.text.contains("Alpha-only marker: 4c2a"))
        assertFalse(alphaPassage.text.contains("Beta-only marker: 9d7f"))
        assertTrue(betaPassage.text.contains("Beta-only marker: 9d7f"))
        assertFalse(betaPassage.text.contains("Alpha-only marker: 4c2a"))
        assertEquals(alpha.citedPaperAccess?.evidenceIndexing?.assetId, alphaPassage.sourceAssetId)
        assertEquals(beta.citedPaperAccess?.evidenceIndexing?.assetId, betaPassage.sourceAssetId)
        assertEquals(expectedSha256(paperTexts.getValue("fixture://cited/alpha").toByteArray(Charsets.UTF_8)), alphaPassage.contentSha256)
        assertEquals(expectedSha256(paperTexts.getValue("fixture://cited/beta").toByteArray(Charsets.UTF_8)), betaPassage.contentSha256)
        assertEquals("fixture-cited-paper-parser", alphaPassage.parserProvider)
        assertEquals("v7", betaPassage.parserVersion)
        assertEquals("en", alphaPassage.language)
        assertEquals("0.6", betaPassage.languageDetectorVersion)
        assertEquals(0, alphaPassage.sectionOrder)
        assertEquals(0, betaPassage.sectionOrder)
        assertEquals("Results", alphaPassage.sectionHeading)
        assertEquals("Results", betaPassage.sectionHeading)
        assertEquals(1, alphaPassage.vectorRank)
        assertEquals(1, alphaPassage.lexicalRank)
        assertEquals(1, alphaPassage.fusedRank)
        assertEquals(1, betaPassage.vectorRank)
        assertEquals(1, betaPassage.lexicalRank)
        assertEquals(1, betaPassage.fusedRank)
        assertEquals("postgres-hybrid-rrf-v1", alphaPassage.retrievalProfile.profileId)
        assertEquals(384, alphaPassage.retrievalProfile.embeddingDimension)
        assertEquals(3, alphaPassage.retrievalProfile.vectorCandidateLimit)
        assertEquals(3, alphaPassage.retrievalProfile.lexicalCandidateLimit)
        assertEquals(3, alphaPassage.retrievalProfile.finalCandidateLimit)
        assertTrue(alphaPassage.retrievalProfile.embeddingProfileHash.matches(Regex("[0-9a-f]{64}")))
        assertTrue(alphaPassage.fusionScore > 0)
        assertNotEquals(alphaPassage.sourceAssetId, betaPassage.sourceAssetId)
    }

    @Test
    fun `System One processing failure leaves each pair incomplete and completes with warnings`() {
        val created = createQueuedRun()
        val resolutionService = referenceResolutionService()
        val documentEvent = jdbc.queryForObject(
            "SELECT payload::text FROM outbox_events WHERE analysis_run_id = ? AND event_type = ?",
            String::class.java,
            created.analysisRunId,
            DOCUMENT_ANALYSIS_REQUESTED,
        )!!
        eventHandler(resolutionService = resolutionService).handle(documentEvent)

        val resolutionEvents = jdbc.query(
            "SELECT payload::text FROM outbox_events WHERE analysis_run_id = ? AND event_type = ? ORDER BY created_at, event_id",
            { rs, _ -> rs.getString(1) },
            created.analysisRunId,
            REFERENCE_RESOLUTION_REQUESTED,
        )
        val resolutionHandler = referenceResolutionEventHandler(resolutionService)
        resolutionEvents.forEach(resolutionHandler::handle)

        val acquisitionEvents = jdbc.query(
            "SELECT payload::text FROM outbox_events WHERE analysis_run_id = ? AND event_type = ? ORDER BY created_at, event_id",
            { rs, _ -> rs.getString(1) },
            created.analysisRunId,
            CITED_PAPER_ACQUISITION_REQUESTED,
        )
        val acquisitionHandler = citedPaperAccessEventHandler(resolutionService)
        acquisitionEvents.forEach(acquisitionHandler::handle)

        val serializedIndexingEvent = jdbc.queryForObject(
            "SELECT payload::text FROM outbox_events WHERE analysis_run_id = ? AND event_type = ?",
            String::class.java,
            created.analysisRunId,
            CITED_PAPER_INDEXING_REQUESTED,
        )!!
        val indexingEvent: PipelineEvent<CitedPaperIndexingRequestedPayload> = objectMapper.readValue(serializedIndexingEvent)
        val failingProvider = object : SystemOneProvider {
            override val providerId = "mock"
            override val version = "v1"
            override val modelId = "mock-v1"
            override fun evaluate(request: SemanticJudgementRequest): SemanticJudgementResult =
                throw IllegalStateException("Simulated System One outage.")
        }
        val indexingHandler = citedPaperIndexingEventHandler(
            resolutionService = resolutionService,
            systemOneProvider = failingProvider,
        )

        assertThrows(IllegalStateException::class.java) { indexingHandler.handle(serializedIndexingEvent) }
        indexingHandler.markFailed(indexingEvent, "System One retry limit reached.")

        val report = resolutionService.report(created.analysisRunId)!!
        val failedPairs = report.referenceResolution.entries.single { it.localReferenceKey == "ref1" }.verificationOutcomes
        assertEquals(2, failedPairs.size)
        assertTrue(failedPairs.all { it.processingStatus == "INCOMPLETE" })
        assertTrue(failedPairs.all { it.processingFailureReason == "EVIDENCE_VERIFICATION_RETRIES_EXHAUSTED" })
        assertTrue(failedPairs.all { it.finalStatus == null })
        assertEquals("COMPLETED_WITH_WARNINGS", report.runStatus)
        assertEquals("COMPLETED_WITH_WARNINGS", report.evidenceCoverage.executionStatus)
        assertEquals(2, report.evidenceCoverage.summary.incompleteVerifications)
        assertEquals("COMPLETED_WITH_WARNINGS", jdbc.queryForObject(
            "SELECT status FROM analysis_runs WHERE id = ?",
            String::class.java,
            created.analysisRunId,
        ))
    }

    @Test
    fun `analysis run listing uses a stable cursor ordered by creation time and ID`() {
        val timestamps = listOf(
            Instant.parse("2025-01-03T00:00:00Z"),
            Instant.parse("2025-01-02T00:00:00Z"),
            Instant.parse("2025-01-02T00:00:00Z"),
            Instant.parse("2025-01-01T00:00:00Z"),
        )
        val createdRuns = timestamps.map(::createQueuedRun)
        val expectedIds = createdRuns
            .zip(timestamps)
            .sortedWith(compareByDescending<Pair<CreatedRunIds, Instant>> { it.second }.thenByDescending { it.first.analysisRunId.toString() })
            .map { it.first.analysisRunId }
        val service = analysisRunService()

        val firstPage = service.list(limit = 2)
        assertEquals(expectedIds.take(2), firstPage.items.map { it.id })
        assertTrue(firstPage.nextCursor != null)

        val secondPage = service.list(limit = 2, cursorToken = firstPage.nextCursor!!)
        assertEquals(expectedIds.drop(2), secondPage.items.map { it.id })
        assertNull(secondPage.nextCursor)
        assertEquals(expectedIds, (firstPage.items + secondPage.items).map { it.id })

        val invalidCursor = assertThrows(IllegalArgumentException::class.java) {
            service.list(limit = 2, cursorToken = "not-a-cursor")
        }
        assertEquals("Analysis Run cursor is invalid.", invalidCursor.message)
    }

    @Test
    fun `persists exact external provider consent in the immutable run snapshot`() {
        val providerCatalog = configuredExternalProviderCatalog()
        val consent = externalProviderConsent(providerCatalog, "configured-llm", listOf("citation_context"))
        val created = analysisRunService(providerCatalog = providerCatalog).createFromUpload(
            "paper.pdf",
            "application/pdf",
            englishPdf(),
            RunConfigurationRequest(claimExtractorProvider = "configured-llm", externalProviderConsents = listOf(consent)),
        )

        assertEquals(
            "EXTERNAL",
            jdbc.queryForObject(
                "SELECT configuration_snapshot #>> '{claimExtractor,trustBoundary}' FROM analysis_runs WHERE id = ?",
                String::class.java,
                created.analysisRunId,
            ),
        )
        assertEquals(
            "citation_context",
            jdbc.queryForObject(
                "SELECT configuration_snapshot #>> '{externalProviderConsents,0,dataCategories,0}' FROM analysis_runs WHERE id = ?",
                String::class.java,
                created.analysisRunId,
            ),
        )
        assertEquals(
            "configured-llm",
            jdbc.queryForObject(
                "SELECT configuration_snapshot #>> '{externalProviderConsents,0,providerId}' FROM analysis_runs WHERE id = ?",
                String::class.java,
                created.analysisRunId,
            ),
        )
        assertEquals(
            "Retention and deletion terms for this controlled test provider.",
            jdbc.queryForObject(
                "SELECT configuration_snapshot #>> '{externalProviderConsents,0,retentionDisclosure}' FROM analysis_runs WHERE id = ?",
                String::class.java,
                created.analysisRunId,
            ),
        )
        assertThrows(DataAccessException::class.java) {
            jdbc.update("UPDATE analysis_runs SET configuration_snapshot = '{}'::jsonb WHERE id = ?", created.analysisRunId)
        }
    }

    @Test
    fun `keeps source object when database commit outcome is uncertain`() {
        val pdf = englishPdf()
        val service = analysisRunService(
            TransactionTemplate(object : PlatformTransactionManager {
                override fun getTransaction(definition: TransactionDefinition?): TransactionStatus = SimpleTransactionStatus(true)

                override fun commit(status: TransactionStatus) {
                    throw TransactionSystemException("Simulated lost commit acknowledgement")
                }

                override fun rollback(status: TransactionStatus) = Unit
            }),
        )

        assertThrows(TransactionSystemException::class.java) {
            service.createFromUpload("paper.pdf", "application/pdf", pdf, RunConfigurationRequest())
        }

        val objectKey = jdbc.queryForObject(
            "SELECT object_key FROM source_documents WHERE sha256 = ?",
            String::class.java,
            expectedSha256(pdf),
        )
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM analysis_runs", Int::class.java))
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM outbox_events", Int::class.java))
        assertArrayEquals(pdf, objectStore.get(objectKey!!))
    }

    @Test
    fun `cleans unreferenced raw parser output when parsed-data persistence rolls back`() {
        val created = createQueuedRun()
        val event = jdbc.queryForObject(
            "SELECT payload::text FROM outbox_events WHERE analysis_run_id = ? AND event_type = ?",
            String::class.java,
            created.analysisRunId,
            DOCUMENT_ANALYSIS_REQUESTED,
        )!!
        val transactionTemplate = TransactionTemplate(
            RollbackOnNthCommitTransactionManager(DataSourceTransactionManager(dataSource), failingCommit = 2),
        )
        val handler = eventHandler(transactionTemplate = transactionTemplate)

        assertThrows(TransactionSystemException::class.java) { handler.handle(event) }

        val rawTeiKey = "source/${created.documentId}/analysis-runs/${created.analysisRunId}/grobid-${expectedSha256("<TEI>test GROBID output</TEI>".toByteArray())}.xml"
        assertThrows(IllegalStateException::class.java) { objectStore.get(rawTeiKey) }
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM parsed_document_parses WHERE analysis_run_id = ?", Int::class.java, created.analysisRunId))
        assertEquals("PROCESSING", jdbc.queryForObject("SELECT status FROM analysis_runs WHERE id = ?", String::class.java, created.analysisRunId))
    }

    @Test
    fun `duplicate Redis delivery is applied once and acknowledged only after inbox commit`() {
        val created = createQueuedRun()
        val stream = "ae:test:${UUID.randomUUID()}"
        val group = "group-${UUID.randomUUID()}"
        val operations = redis.opsForStream<String, String>()
        operations.add(stream, mapOf("bootstrap" to "1"))
        operations.createGroup(stream, ReadOffset.from("$"), group)

        val publisher = OutboxPublisher(jdbc, redis, stream)
        publisher.publishPending()
        val envelope = jdbc.queryForObject("SELECT payload::text FROM outbox_events WHERE analysis_run_id = ?", String::class.java, created.analysisRunId)
        operations.add(stream, mapOf("event" to envelope!!)) // Simulate a repeated at-least-once delivery.
        val messages = operations.read(
            Consumer.from(group, "duplicate-test"),
            StreamReadOptions.empty().count(10),
            StreamOffset.create(stream, ReadOffset.lastConsumed()),
        ).orEmpty()
        assertEquals(2, messages.size, "Unexpected stream entries: ${operations.range(stream, Range.unbounded<String>()).orEmpty().map { it.value }}")

        val handler = eventHandler()
        messages.forEach { record ->
            handler.handle(record.value.getValue("event"))
            operations.acknowledge(stream, group, record.id)
        }
        processReferenceResolutionEvents(created.analysisRunId)

        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM inbox_events WHERE event_id = (SELECT event_id FROM outbox_events WHERE analysis_run_id = ? AND event_type = 'DocumentAnalysisRequested')", Int::class.java, created.analysisRunId))
        assertEquals("COMPLETED", jdbc.queryForObject("SELECT status FROM analysis_runs WHERE id = ?", String::class.java, created.analysisRunId))
        assertNotEquals(null, jdbc.queryForObject("SELECT completed_at FROM analysis_runs WHERE id = ?", Timestamp::class.java, created.analysisRunId))
        assertEquals(
            "COMPLETED",
            jdbc.queryForObject("SELECT progress ->> 'stage' FROM analysis_runs WHERE id = ?", String::class.java, created.analysisRunId),
        )
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM parsed_document_sections WHERE analysis_run_id = ?", Int::class.java, created.analysisRunId))
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM citation_contexts WHERE analysis_run_id = ?", Int::class.java, created.analysisRunId))
        assertEquals(3, jdbc.queryForObject("SELECT count(*) FROM citation_targets WHERE analysis_run_id = ?", Int::class.java, created.analysisRunId))
        assertEquals(3, jdbc.queryForObject("SELECT count(*) FROM atomic_claims WHERE analysis_run_id = ?", Int::class.java, created.analysisRunId))
        assertEquals(3, jdbc.queryForObject("SELECT (progress ->> 'atomicClaimCount')::integer FROM analysis_runs WHERE id = ?", Int::class.java, created.analysisRunId))
        assertEquals(5, jdbc.queryForObject("SELECT count(*) FROM atomic_claim_citation_targets WHERE analysis_run_id = ?", Int::class.java, created.analysisRunId))
        assertThrows(DataAccessException::class.java) {
            jdbc.update("UPDATE parsed_document_parses SET parser_version = 'changed' WHERE analysis_run_id = ?", created.analysisRunId)
        }
        val parsed = ParsedDocumentRepository(jdbc, objectMapper).find(created.analysisRunId)!!
        assertEquals("grobid", parsed.parser.provider)
        assertEquals("0.9.1-crf", parsed.parser.version)
        assertEquals(created.hash, parsed.sourceContentSha256)
        assertEquals("Prior results support the method and reproduce it [1, 2]; however, later work disputes it [3].", parsed.normalizedSourceText)
        assertEquals(2, parsed.citationContexts.size)
        assertEquals("Prior results support the method and reproduce it [1, 2]", parsed.citationContexts[0].text)
        assertEquals("[1, 2]", parsed.citationContexts[0].occurrences.single().markerText)
        assertEquals(listOf("ref2", "ref1"), parsed.citationContexts[0].occurrences.single().bibliographyReferenceKeys)
        assertEquals("however, later work disputes it [3].", parsed.citationContexts[1].text)
        assertEquals("[3]", parsed.citationContexts[1].occurrences.single().markerText)
        assertEquals(listOf("ref3"), parsed.citationContexts[1].occurrences.single().bibliographyReferenceKeys)
        assertEquals(listOf("ref1", "ref2", "ref3", "ref4"), parsed.bibliographyEntries.map { it.localReferenceKey })
        assertEquals(
            listOf("RESOLVED", "UNSUPPORTED_REFERENCE_TYPE", "UNSUPPORTED_REFERENCE_TYPE", "UNRESOLVED"),
            parsed.bibliographyEntries.map { it.resolutionStatus },
        )
        assertEquals(listOf("Prior results support the method", "Prior results reproduce it"), parsed.citationContexts[0].atomicClaims.map { it.text })
        assertEquals("however, later work disputes it", parsed.citationContexts[1].atomicClaims.single().text)
        assertEquals(listOf("reproduce it"), parsed.citationContexts[0].atomicClaims.drop(1).map { parsed.normalizedSourceText.substring(it.sourceStartOffset, it.sourceEndOffset) })
        assertTrue(parsed.citationContexts.flatMap { it.atomicClaims }.flatMap { it.citationTargets }
            .all { it.associationKind == "INFERRED_PROVISIONAL" })
        parsed.citationContexts[0].atomicClaims.forEach { claim ->
            assertEquals(listOf("ref2", "ref1"), claim.citationTargets.map { it.bibliographyReferenceKey })
        }
        assertEquals(listOf("ref3"), parsed.citationContexts[1].atomicClaims.single().citationTargets.map { it.bibliographyReferenceKey })
        assertThrows(DataAccessException::class.java) {
            jdbc.update("UPDATE atomic_claims SET claim_text = 'changed' WHERE id = ?", parsed.citationContexts[0].atomicClaims.first().id)
        }
        assertThrows(DataAccessException::class.java) {
            jdbc.update(
                "UPDATE atomic_claim_citation_targets SET created_at = now() WHERE atomic_claim_id = ?",
                parsed.citationContexts[0].atomicClaims.first().id,
            )
        }
        val firstReferenceId = jdbc.queryForObject(
            "SELECT id FROM bibliography_entries WHERE analysis_run_id = ? AND local_reference_key = 'ref1'",
            UUID::class.java,
            created.analysisRunId,
        )!!
        assertThrows(DataAccessException::class.java) {
            jdbc.update(
                """INSERT INTO citation_targets (id, analysis_run_id, citation_context_id, citation_occurrence_id, bibliography_entry_id, target_order)
                   VALUES (?, ?, ?, ?, ?, 1)""",
                UUID.randomUUID(),
                created.analysisRunId,
                parsed.citationContexts[0].id,
                parsed.citationContexts[1].occurrences.single().id,
                firstReferenceId,
            )
        }
        val duplicateClaim = parsed.citationContexts[0].atomicClaims.first()
        assertThrows(DataAccessException::class.java) {
            jdbc.update(
                """INSERT INTO atomic_claims (id, analysis_run_id, citation_context_id, claim_text, source_start_offset, source_end_offset)
                   VALUES (?, ?, ?, ?, ?, ?)""",
                UUID.randomUUID(),
                created.analysisRunId,
                parsed.citationContexts[0].id,
                duplicateClaim.text,
                duplicateClaim.sourceStartOffset,
                duplicateClaim.sourceEndOffset,
            )
        }
        assertThrows(DataAccessException::class.java) {
            jdbc.update(
                """INSERT INTO atomic_claim_citation_targets (id, analysis_run_id, citation_context_id, atomic_claim_id, citation_target_id, association_kind)
                   VALUES (?, ?, ?, ?, ?, 'INFERRED_PROVISIONAL')""",
                UUID.randomUUID(),
                created.analysisRunId,
                parsed.citationContexts[0].id,
                parsed.citationContexts[0].atomicClaims.first().id,
                parsed.citationContexts[1].atomicClaims.single().citationTargets.single().id,
            )
        }
        val report = referenceResolutionService().report(created.analysisRunId)!!
        assertEquals("COMPLETED", report.referenceResolution.executionStatus)
        assertEquals("title-author-year-weighted-edit-similarity-v1", report.referenceResolution.scorePolicyVersion)
        assertEquals(0.25, report.referenceResolution.confidenceThreshold)
        assertEquals(4, report.referenceResolution.summary.total)
        assertEquals(1, report.referenceResolution.summary.resolved)
        assertEquals(1, report.referenceResolution.summary.unresolved)
        assertEquals(2, report.referenceResolution.summary.unsupportedReferenceType)
        assertEquals("10.5555/papertrail.fixture.reference-resolution.2024", report.referenceResolution.entries[0].canonicalPaper?.doi)
        assertEquals("CONFIRMED_DOI", report.referenceResolution.entries[0].matchMethod)
        assertEquals("UNSUPPORTED_REFERENCE_TYPE", report.referenceResolution.entries[1].reasonCode)
        assertEquals("UNRESOLVED", report.referenceResolution.entries[3].status)
        assertEquals("BELOW_CONFIDENCE_THRESHOLD", report.referenceResolution.entries[3].reasonCode)
        assertNull(report.referenceResolution.entries[3].canonicalPaper)
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM bibliography_entry_resolutions WHERE analysis_run_id = ? AND canonical_paper_id IS NOT NULL", Int::class.java, created.analysisRunId))
        assertEquals(4, jdbc.queryForObject("SELECT count(*) FROM bibliography_entry_resolutions WHERE analysis_run_id = ?", Int::class.java, created.analysisRunId))
        assertThrows(DataAccessException::class.java) {
            jdbc.update("UPDATE bibliography_entry_resolutions SET reason_code = 'changed' WHERE analysis_run_id = ?", created.analysisRunId)
        }
        val persistedTargetLinks = jdbc.query(
            """
            SELECT o.marker_text, b.local_reference_key
              FROM citation_occurrences o
              JOIN citation_targets t ON t.analysis_run_id = o.analysis_run_id AND t.citation_occurrence_id = o.id
              JOIN bibliography_entries b ON b.analysis_run_id = t.analysis_run_id AND b.id = t.bibliography_entry_id
             WHERE o.analysis_run_id = ?
             ORDER BY o.start_offset, t.target_order
            """.trimIndent(),
            { rs, _ -> "${rs.getString("marker_text")} -> ${rs.getString("local_reference_key")}" },
            created.analysisRunId,
        )
        assertEquals(listOf("[1, 2] -> ref2", "[1, 2] -> ref1", "[3] -> ref3"), persistedTargetLinks)
        parsed.citationContexts.flatMap { it.occurrences }.forEach { occurrence ->
            assertEquals(occurrence.markerText, parsed.normalizedSourceText.substring(occurrence.startOffset, occurrence.endOffset))
        }
        val rawTeiObjectKey = jdbc.queryForObject(
            "SELECT raw_tei_object_key FROM parsed_document_parses WHERE analysis_run_id = ?",
            String::class.java,
            created.analysisRunId,
        )!!
        assertTrue(rawTeiObjectKey.startsWith("source/${created.documentId}/analysis-runs/${created.analysisRunId}/grobid-"))
        assertTrue(rawTeiObjectKey.endsWith(".xml"))
        assertArrayEquals("<TEI>test GROBID output</TEI>".toByteArray(), objectStore.get(rawTeiObjectKey))
        assertEquals("application/xml", objectStore.contentType(rawTeiObjectKey))
        assertEquals(0L, operations.pending(stream, group)?.totalPendingMessages ?: 0L)
        val processedEvent: PipelineEvent<DocumentAnalysisRequestedPayload> = objectMapper.readValue(envelope)
        handler.markFailed(processedEvent, "A stale retry must not overwrite a committed success.")
        assertEquals("COMPLETED", jdbc.queryForObject("SELECT status FROM analysis_runs WHERE id = ?", String::class.java, created.analysisRunId))
        assertTrue(jdbc.queryForObject("SELECT failure_reason IS NULL FROM analysis_runs WHERE id = ?", Boolean::class.java, created.analysisRunId) == true)
    }

    @Test
    fun `a replacement worker reclaims and commits work left pending by a crashed worker`() {
        val created = createQueuedRun()
        val stream = "ae:test:${UUID.randomUUID()}"
        val group = "group-${UUID.randomUUID()}"
        val operations = redis.opsForStream<String, String>()
        operations.add(stream, mapOf("bootstrap" to "1"))
        operations.createGroup(stream, ReadOffset.from("$"), group)
        OutboxPublisher(jdbc, redis, stream).publishPending()

        val abandoned = operations.read(
            Consumer.from(group, "crashed-worker"),
            StreamReadOptions.empty().count(1),
            StreamOffset.create(stream, ReadOffset.lastConsumed()),
        ).orEmpty().single()
        assertEquals(1L, operations.pending(stream, group)?.totalPendingMessages ?: 0L)

        val replacement = RedisStreamWorker(
            redis = redis,
            handler = eventHandler(),
            referenceResolutionHandler = referenceResolutionEventHandler(),
            objectMapper = objectMapper,
            stream = stream,
            group = group,
            citedPaperAcquisitionHandler = citedPaperAccessEventHandler(),
            citedPaperIndexingHandler = citedPaperIndexingEventHandler(),
            consumerName = "replacement-worker",
            reclaimDelayMs = 0,
            batchSize = 10,
            maxAttempts = 3,
            retryBackoffMs = "0,0,0",
        )
        replacement.createConsumerGroup()
        val workerLogger = LoggerFactory.getLogger(RedisStreamWorker::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        workerLogger.addAppender(appender)
        try {
            replacement.poll()
        } finally {
            workerLogger.detachAppender(appender)
        }

        OutboxPublisher(jdbc, redis, stream).publishPending()
        replacement.poll()
        OutboxPublisher(jdbc, redis, stream).publishPending()
        replacement.poll()
        OutboxPublisher(jdbc, redis, stream).publishPending()
        replacement.poll()
        assertEquals("COMPLETED", jdbc.queryForObject("SELECT status FROM analysis_runs WHERE id = ?", String::class.java, created.analysisRunId))
        val processedEvent = objectMapper.readTree(abandoned.value.getValue("event"))
        val workerLog = appender.list.single { it.message == "Pipeline event processed" }
        val mdc = workerLog.mdcPropertyMap
        assertEquals(processedEvent.path("analysisRunId").asText(), mdc["analysisRunId"])
        assertEquals(processedEvent.path("eventId").asText(), mdc["eventId"])
        assertEquals(processedEvent.path("correlationId").asText(), mdc["correlationId"])
        assertEquals(processedEvent.path("payload").path("documentId").asText(), mdc["documentId"])
        assertEquals(processedEvent.path("eventType").asText(), mdc["eventType"])
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM inbox_events WHERE event_id = ?", Int::class.java, UUID.fromString(envelopeEventId(abandoned.value.getValue("event")))))
        assertEquals(0L, operations.pending(stream, group)?.totalPendingMessages ?: 0L)
    }

    @Test
    fun `active processing lease prevents another worker from reclaiming slow work`() {
        val created = createQueuedRun()
        val stream = "ae:test:${UUID.randomUUID()}"
        val group = "group-${UUID.randomUUID()}"
        val operations = redis.opsForStream<String, String>()
        operations.add(stream, mapOf("bootstrap" to "1"))
        operations.createGroup(stream, ReadOffset.from("$"), group)
        OutboxPublisher(jdbc, redis, stream).publishPending()

        val parserStarted = CountDownLatch(1)
        val continueParsing = CountDownLatch(1)
        val slowParser = BlockingScientificDocumentParser(parserStarted, continueParsing)
        val activeWorker = RedisStreamWorker(
            redis = redis,
            handler = eventHandler(slowParser),
            referenceResolutionHandler = referenceResolutionEventHandler(),
            objectMapper = objectMapper,
            stream = stream,
            group = group,
            citedPaperAcquisitionHandler = citedPaperAccessEventHandler(),
            citedPaperIndexingHandler = citedPaperIndexingEventHandler(),
            consumerName = "active-worker",
            reclaimDelayMs = 300,
            batchSize = 10,
            maxAttempts = 3,
            retryBackoffMs = "0,0,0",
        )
        val replacementWorker = RedisStreamWorker(
            redis = redis,
            handler = eventHandler(slowParser),
            referenceResolutionHandler = referenceResolutionEventHandler(),
            objectMapper = objectMapper,
            stream = stream,
            group = group,
            citedPaperAcquisitionHandler = citedPaperAccessEventHandler(),
            citedPaperIndexingHandler = citedPaperIndexingEventHandler(),
            consumerName = "replacement-worker",
            reclaimDelayMs = 300,
            batchSize = 10,
            maxAttempts = 3,
            retryBackoffMs = "0,0,0",
        )
        activeWorker.createConsumerGroup()
        replacementWorker.createConsumerGroup()
        val activePoll = Thread(activeWorker::poll).apply { start() }
        try {
            assertTrue(parserStarted.await(2, TimeUnit.SECONDS), "Active worker did not start parsing.")
            Thread.sleep(750)
            replacementWorker.poll()

            assertEquals(1, slowParser.parseCalls.get())
            assertEquals("PROCESSING", jdbc.queryForObject("SELECT status FROM analysis_runs WHERE id = ?", String::class.java, created.analysisRunId))
        } finally {
            continueParsing.countDown()
            activePoll.join(10_000)
            activeWorker.shutdownLeaseHeartbeat()
        }

        assertFalse(activePoll.isAlive, "Active worker did not finish after parsing was released.")
        OutboxPublisher(jdbc, redis, stream).publishPending()
        try {
            replacementWorker.poll()
            OutboxPublisher(jdbc, redis, stream).publishPending()
            replacementWorker.poll()
            OutboxPublisher(jdbc, redis, stream).publishPending()
            replacementWorker.poll()
        } finally {
            replacementWorker.shutdownLeaseHeartbeat()
        }
        assertEquals("COMPLETED", jdbc.queryForObject("SELECT status FROM analysis_runs WHERE id = ?", String::class.java, created.analysisRunId))
        assertEquals(0L, operations.pending(stream, group)?.totalPendingMessages ?: 0L)
    }

    @Test
    fun `reference resolution retries only the failed bibliography entry`() {
        val created = createQueuedRun()
        val lookupFactory = RetryableDoiLookupFactory(failOnlyFirstDoiLookup = true)
        val resolutionService = referenceResolutionService(listOf(lookupFactory))
        val stream = "ae:test:${UUID.randomUUID()}"
        val group = "group-${UUID.randomUUID()}"
        val operations = redis.opsForStream<String, String>()
        operations.add(stream, mapOf("bootstrap" to "1"))
        operations.createGroup(stream, ReadOffset.from("$"), group)
        val worker = RedisStreamWorker(
            redis = redis,
            handler = eventHandler(resolutionService = resolutionService),
            referenceResolutionHandler = referenceResolutionEventHandler(resolutionService),
            objectMapper = objectMapper,
            stream = stream,
            group = group,
            citedPaperAcquisitionHandler = citedPaperAccessEventHandler(resolutionService),
            citedPaperIndexingHandler = citedPaperIndexingEventHandler(resolutionService),
            consumerName = "reference-retry-worker",
            reclaimDelayMs = 0,
            batchSize = 10,
            maxAttempts = 3,
            retryBackoffMs = "0,0,0",
        )
        worker.createConsumerGroup()

        try {
            OutboxPublisher(jdbc, redis, stream).publishPending()
            worker.poll()
            assertEquals("PROCESSING", jdbc.queryForObject("SELECT status FROM analysis_runs WHERE id = ?", String::class.java, created.analysisRunId))
            assertEquals(4, jdbc.queryForObject(
                "SELECT count(*) FROM outbox_events WHERE analysis_run_id = ? AND event_type = ?",
                Int::class.java,
                created.analysisRunId,
                REFERENCE_RESOLUTION_REQUESTED,
            ))

            OutboxPublisher(jdbc, redis, stream).publishPending()
            worker.poll()
            assertEquals("PROCESSING", jdbc.queryForObject("SELECT status FROM analysis_runs WHERE id = ?", String::class.java, created.analysisRunId))
            assertEquals(3, jdbc.queryForObject(
                "SELECT count(*) FROM bibliography_entry_resolutions WHERE analysis_run_id = ?",
                Int::class.java,
                created.analysisRunId,
            ))
            assertEquals(1L, operations.pending(stream, group)?.totalPendingMessages ?: 0L)

            val failedEntryId = jdbc.queryForObject(
                "SELECT id FROM bibliography_entries WHERE analysis_run_id = ? AND local_reference_key = 'ref1'",
                UUID::class.java,
                created.analysisRunId,
            )!!
            val failedMessage = operations.range(stream, Range.unbounded<String>()).orEmpty().single { record ->
                record.value["event"]?.let { serialized ->
                    objectMapper.readTree(serialized).path("payload").path("bibliographyEntryId").asText() == failedEntryId.toString()
                } == true
            }
            redis.opsForHash<String, String>().put("$stream:retry-after", failedMessage.id.value, "0")
            worker.poll()
            OutboxPublisher(jdbc, redis, stream).publishPending()
            worker.poll()
            OutboxPublisher(jdbc, redis, stream).publishPending()
            worker.poll()

            assertEquals("COMPLETED", jdbc.queryForObject("SELECT status FROM analysis_runs WHERE id = ?", String::class.java, created.analysisRunId))
            assertEquals(4, jdbc.queryForObject(
                "SELECT count(*) FROM bibliography_entry_resolutions WHERE analysis_run_id = ?",
                Int::class.java,
                created.analysisRunId,
            ))
            assertEquals(2, lookupFactory.doiLookupCalls.get())
            assertEquals(0L, operations.pending(stream, group)?.totalPendingMessages ?: 0L)
        } finally {
            worker.shutdownLeaseHeartbeat()
        }
    }

    @Test
    fun `exhausted per-entry retries complete the run with a visible resolution failure`() {
        val created = createQueuedRun()
        val lookupFactory = RetryableDoiLookupFactory(failOnlyFirstDoiLookup = false)
        val resolutionService = referenceResolutionService(listOf(lookupFactory))
        val stream = "ae:test:${UUID.randomUUID()}"
        val group = "group-${UUID.randomUUID()}"
        val operations = redis.opsForStream<String, String>()
        operations.add(stream, mapOf("bootstrap" to "1"))
        operations.createGroup(stream, ReadOffset.from("$"), group)
        val worker = RedisStreamWorker(
            redis = redis,
            handler = eventHandler(resolutionService = resolutionService),
            referenceResolutionHandler = referenceResolutionEventHandler(resolutionService),
            objectMapper = objectMapper,
            stream = stream,
            group = group,
            citedPaperAcquisitionHandler = citedPaperAccessEventHandler(resolutionService),
            citedPaperIndexingHandler = citedPaperIndexingEventHandler(resolutionService),
            consumerName = "reference-failure-worker",
            reclaimDelayMs = 0,
            batchSize = 10,
            maxAttempts = 2,
            retryBackoffMs = "0,0",
        )
        worker.createConsumerGroup()

        try {
            OutboxPublisher(jdbc, redis, stream).publishPending()
            worker.poll()
            OutboxPublisher(jdbc, redis, stream).publishPending()
            worker.poll()
            assertEquals("PROCESSING", jdbc.queryForObject("SELECT status FROM analysis_runs WHERE id = ?", String::class.java, created.analysisRunId))

            val failedEntryId = jdbc.queryForObject(
                "SELECT id FROM bibliography_entries WHERE analysis_run_id = ? AND local_reference_key = 'ref1'",
                UUID::class.java,
                created.analysisRunId,
            )!!
            val failedMessage = operations.range(stream, Range.unbounded<String>()).orEmpty().single { record ->
                record.value["event"]?.let { serialized ->
                    objectMapper.readTree(serialized).path("payload").path("bibliographyEntryId").asText() == failedEntryId.toString()
                } == true
            }
            redis.opsForHash<String, String>().put("$stream:retry-after", failedMessage.id.value, "0")
            worker.poll()

            assertEquals("COMPLETED_WITH_WARNINGS", jdbc.queryForObject("SELECT status FROM analysis_runs WHERE id = ?", String::class.java, created.analysisRunId))
            assertEquals(1, jdbc.queryForObject(
                "SELECT (progress ->> 'failedReferenceResolutionCount')::integer FROM analysis_runs WHERE id = ?",
                Int::class.java,
                created.analysisRunId,
            ))
            assertTrue(jdbc.queryForObject("SELECT completed_at IS NOT NULL FROM analysis_runs WHERE id = ?", Boolean::class.java, created.analysisRunId) == true)
            assertEquals(3, jdbc.queryForObject(
                "SELECT count(*) FROM bibliography_entry_resolutions WHERE analysis_run_id = ?",
                Int::class.java,
                created.analysisRunId,
            ))
            val report = resolutionService.report(created.analysisRunId)!!
            assertEquals("COMPLETED_WITH_WARNINGS", report.referenceResolution.executionStatus)
            assertEquals(1, report.referenceResolution.summary.failed)
            assertEquals(0, report.referenceResolution.summary.notAttempted)
            assertEquals("RESOLUTION_FAILED", report.referenceResolution.entries.first().status)
            assertEquals("RESOLUTION_RETRIES_EXHAUSTED", report.referenceResolution.entries.first().reasonCode)
            assertEquals(2, lookupFactory.doiLookupCalls.get())
        } finally {
            worker.shutdownLeaseHeartbeat()
        }
    }

    @Test
    fun `marks a persistently failing queued run failed and dead-letters it after three deliveries`() {
        val created = createQueuedRun()
        objectStore.delete("source/${created.documentId}/${created.hash}.pdf")
        val stream = "ae:test:${UUID.randomUUID()}"
        val group = "group-${UUID.randomUUID()}"
        val operations = redis.opsForStream<String, String>()
        operations.add(stream, mapOf("bootstrap" to "1"))
        operations.createGroup(stream, ReadOffset.from("$"), group)
        OutboxPublisher(jdbc, redis, stream).publishPending()

        val worker = RedisStreamWorker(
            redis = redis,
            handler = eventHandler(),
            referenceResolutionHandler = referenceResolutionEventHandler(),
            objectMapper = objectMapper,
            stream = stream,
            group = group,
            citedPaperAcquisitionHandler = citedPaperAccessEventHandler(),
            citedPaperIndexingHandler = citedPaperIndexingEventHandler(),
            consumerName = "retry-worker",
            reclaimDelayMs = 0,
            batchSize = 10,
            maxAttempts = 3,
            retryBackoffMs = "0,0,0",
        )
        worker.createConsumerGroup()
        worker.poll()
        assertEquals("QUEUED", jdbc.queryForObject("SELECT status FROM analysis_runs WHERE id = ?", String::class.java, created.analysisRunId))

        val message = operations.range(stream, Range.unbounded<String>()).orEmpty().single { it.value.containsKey("event") }
        val event = objectMapper.readTree(message.value.getValue("event"))
        val retrySchedule = "$stream:retry-after"
        val workerLogger = LoggerFactory.getLogger(RedisStreamWorker::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        workerLogger.addAppender(appender)
        try {
            repeat(2) { delivery ->
                redis.opsForHash<String, String>().put(retrySchedule, message.id.value, "0")
                worker.poll()
                val expectedStatus = if (delivery == 1) "FAILED" else "QUEUED"
                assertEquals(expectedStatus, jdbc.queryForObject("SELECT status FROM analysis_runs WHERE id = ?", String::class.java, created.analysisRunId))
            }
        } finally {
            workerLogger.detachAppender(appender)
        }

        val finalFailureLog = appender.list.single { it.message == "Pipeline event failed on its final attempt" }
        assertEquals(event.path("analysisRunId").asText(), finalFailureLog.mdcPropertyMap["analysisRunId"])
        assertEquals(event.path("eventId").asText(), finalFailureLog.mdcPropertyMap["eventId"])
        assertEquals(event.path("correlationId").asText(), finalFailureLog.mdcPropertyMap["correlationId"])
        assertEquals(event.path("payload").path("documentId").asText(), finalFailureLog.mdcPropertyMap["documentId"])
        assertTrue(finalFailureLog.keyValuePairs.any { it.key == "errorType" && it.value == "IllegalStateException" })
        assertFalse(finalFailureLog.formattedMessage.contains("Queued Source Document is missing."))
        assertTrue(jdbc.queryForObject("SELECT failure_reason IS NOT NULL FROM analysis_runs WHERE id = ?", Boolean::class.java, created.analysisRunId) == true)
        val deadLetter = operations.range("ae:dlq", Range.unbounded<String>()).orEmpty().single().value
        assertEquals("HANDLER_RETRIES_EXHAUSTED", deadLetter["errorCode"])
        assertEquals("3", deadLetter["attempts"])
        assertEquals(3, objectMapper.readTree(deadLetter.getValue("event")).get("attempt").asInt())
        assertEquals(0L, operations.pending(stream, group)?.totalPendingMessages ?: 0L)
    }

    @Test
    fun `dead-letters permanent claim-analysis contract failures without retrying`() {
        val settings = OpenAiCompatibleClaimAnalysisSettings(
            endpoint = OpenAiCompatibleEndpointSettings(
                enabled = true,
                baseUrl = "http://127.0.0.1:9123/v1",
                trustedHosts = setOf("127.0.0.1"),
            ),
            modelId = "fixture-model",
        )
        val providerCatalog = ProviderCatalog.safeDefaults(openAiCompatibleClaimAnalysisSettings = settings)
        val configuration = configurationFactory(providerCatalog = providerCatalog).from(
            RunConfigurationRequest(claimExtractorProvider = OpenAiCompatibleClaimAnalysisSettings.PROVIDER_ID),
        )
        val created = createQueuedRun(configurationJson = objectMapper.writeValueAsString(configuration))
        val stream = "ae:test:${UUID.randomUUID()}"
        val group = "group-${UUID.randomUUID()}"
        val operations = redis.opsForStream<String, String>()
        operations.add(stream, mapOf("bootstrap" to "1"))
        operations.createGroup(stream, ReadOffset.from("$"), group)
        OutboxPublisher(jdbc, redis, stream).publishPending()
        var providerCalls = 0
        val failingProvider = object : ClaimAnalysisProvider {
            override val providerId = OpenAiCompatibleClaimAnalysisSettings.PROVIDER_ID
            override val version = OpenAiCompatibleClaimAnalysisSettings.VERSION
            override val modelId: String? = settings.modelId
            override val targetSelectionPolicyVersion = ClaimAnalysisVersions.MODEL_TARGET_SELECTION_POLICY
            override val promptVersion = ClaimAnalysisVersions.OPENAI_COMPATIBLE_PROMPT
            override val outputMappingVersion = ClaimAnalysisVersions.OPENAI_COMPATIBLE_OUTPUT_MAPPING

            override fun analyze(
                request: ClaimAnalysisRequest,
                configuration: AnalysisConfigurationSnapshot,
            ): List<CitationContextClaims> {
                providerCalls++
                throw OpenAiCompatibleProviderException("The selected provider returned an invalid claim-analysis response.")
            }
        }
        val worker = RedisStreamWorker(
            redis = redis,
            handler = eventHandler(
                claimAnalysisProviders = listOf(failingProvider),
                providerCatalog = providerCatalog,
            ),
            referenceResolutionHandler = referenceResolutionEventHandler(),
            objectMapper = objectMapper,
            stream = stream,
            group = group,
            citedPaperAcquisitionHandler = citedPaperAccessEventHandler(),
            citedPaperIndexingHandler = citedPaperIndexingEventHandler(),
            consumerName = "non-retryable-claim-analysis-worker",
            reclaimDelayMs = 0,
            batchSize = 10,
            maxAttempts = 3,
            retryBackoffMs = "0,0,0",
        )

        try {
            worker.createConsumerGroup()
            worker.poll()

            assertEquals(1, providerCalls)
            assertEquals("FAILED", jdbc.queryForObject("SELECT status FROM analysis_runs WHERE id = ?", String::class.java, created.analysisRunId))
            assertEquals(
                "The selected provider returned an invalid claim-analysis response.",
                jdbc.queryForObject("SELECT failure_reason FROM analysis_runs WHERE id = ?", String::class.java, created.analysisRunId),
            )
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM parsed_document_parses WHERE analysis_run_id = ?", Int::class.java, created.analysisRunId))
            val deadLetter = operations.range("ae:dlq", Range.unbounded<String>()).orEmpty().single().value
            assertEquals("HANDLER_NON_RETRYABLE", deadLetter["errorCode"])
            assertEquals("1", deadLetter["attempts"])
            assertEquals(0L, operations.pending(stream, group)?.totalPendingMessages ?: 0L)
        } finally {
            worker.shutdownLeaseHeartbeat()
        }
    }

    @Test
    fun `dead-letters malformed event envelopes instead of leaving them pending`() {
        val stream = "ae:test:${UUID.randomUUID()}"
        val group = "group-${UUID.randomUUID()}"
        val operations = redis.opsForStream<String, String>()
        operations.add(stream, mapOf("bootstrap" to "1"))
        operations.createGroup(stream, ReadOffset.from("$"), group)
        operations.add(stream, mapOf("event" to "not-json"))
        val worker = RedisStreamWorker(
            redis = redis,
            handler = eventHandler(),
            referenceResolutionHandler = referenceResolutionEventHandler(),
            objectMapper = objectMapper,
            stream = stream,
            group = group,
            citedPaperAcquisitionHandler = citedPaperAccessEventHandler(),
            citedPaperIndexingHandler = citedPaperIndexingEventHandler(),
            consumerName = "malformed-worker",
            reclaimDelayMs = 0,
            batchSize = 10,
            maxAttempts = 3,
            retryBackoffMs = "0,0,0",
        )
        worker.createConsumerGroup()

        worker.poll()

        val deadLetter = operations.range("ae:dlq", Range.unbounded<String>()).orEmpty().single().value
        assertEquals("MALFORMED_EVENT_ENVELOPE", deadLetter["errorCode"])
        assertEquals(0L, operations.pending(stream, group)?.totalPendingMessages ?: 0L)
    }

    private class RollbackOnNthCommitTransactionManager(
        private val delegate: PlatformTransactionManager,
        private val failingCommit: Int,
    ) : PlatformTransactionManager {
        private var commitCount = 0
        private var rolledBackStatus: TransactionStatus? = null

        override fun getTransaction(definition: TransactionDefinition?): TransactionStatus = delegate.getTransaction(definition)

        override fun commit(status: TransactionStatus) {
            commitCount++
            if (commitCount == failingCommit) {
                delegate.rollback(status)
                rolledBackStatus = status
                throw TransactionSystemException("Simulated commit failure after transaction rollback.")
            }
            delegate.commit(status)
        }

        override fun rollback(status: TransactionStatus) {
            if (rolledBackStatus === status) {
                rolledBackStatus = null
                return
            }
            delegate.rollback(status)
        }
    }

    private class RetryableDoiLookupFactory(
        private val failOnlyFirstDoiLookup: Boolean,
    ) : ScholarlyMetadataLookupFactory {
        override val providerId = "recorded-fixtures"
        val doiLookupCalls = AtomicInteger()

        override fun forRun(configuration: AnalysisConfigurationSnapshot): ScholarlyMetadataLookup = object : ScholarlyMetadataLookup {
            override fun byDoi(doi: String): ScholarlyWork? {
                if (DoiNormalizer.normalize(doi) != FIXTURE_DOI) return null
                val attempt = doiLookupCalls.incrementAndGet()
                if (!failOnlyFirstDoiLookup || attempt == 1) throw IllegalStateException("Temporary scholarly metadata provider failure.")
                return ScholarlyWork(
                    doi = FIXTURE_DOI,
                    title = "A fixture study of conservative scholarly reference resolution",
                    authors = listOf("Riley Example", "Jordan Researcher"),
                    year = 2024,
                )
            }

            override fun search(reference: BibliographyReference): List<ScholarlyWork> = emptyList()
        }

        companion object {
            private const val FIXTURE_DOI = "10.5555/papertrail.fixture.reference-resolution.2024"
        }
    }

    private class BlockingScientificDocumentParser(
        private val started: CountDownLatch,
        private val proceed: CountDownLatch,
    ) : ScientificDocumentParser {
        val parseCalls = AtomicInteger()

        override fun parse(pdf: ByteArray): ParsedScientificDocument {
            if (parseCalls.incrementAndGet() == 1) {
                started.countDown()
                check(proceed.await(10, TimeUnit.SECONDS)) { "Timed out waiting to release the blocking parser." }
            }
            return TestScientificDocumentParser.parse(pdf)
        }
    }

    private object TestScientificDocumentParser : ScientificDocumentParser {
        override fun parse(pdf: ByteArray): ParsedScientificDocument {
            val firstContext = "Prior results support the method and reproduce it [1, 2]"
            val secondContext = "however, later work disputes it [3]."
            val secondContextStart = firstContext.length + 2
            val text = "$firstContext; $secondContext"
            val firstMarkerStart = text.indexOf("[1, 2]")
            val secondMarkerStart = text.indexOf("[3]")
            return ParsedScientificDocument(
                parserId = "grobid",
                parserVersion = "0.9.1-crf",
                normalizedSourceText = text,
                sections = listOf(ParsedSection(0, "Introduction", text, 0, text.length)),
                citationContexts = listOf(
                    ParsedCitationContext(
                        sectionOrder = 0,
                        boundaryKind = "CLAUSE",
                        text = firstContext,
                        startOffset = 0,
                        endOffset = firstContext.length,
                        occurrences = listOf(ParsedCitationOccurrence("[1, 2]", firstMarkerStart, firstMarkerStart + 6, listOf("ref2", "ref1"))),
                    ),
                    ParsedCitationContext(
                        sectionOrder = 0,
                        boundaryKind = "CLAUSE",
                        text = secondContext,
                        startOffset = secondContextStart,
                        endOffset = text.length,
                        occurrences = listOf(ParsedCitationOccurrence("[3]", secondMarkerStart, secondMarkerStart + 3, listOf("ref3"))),
                    ),
                ),
                bibliographyEntries = listOf(
                    ParsedBibliographyEntry(
                        0,
                        "ref1",
                        "A fixture study of conservative scholarly reference resolution",
                        "A fixture study of conservative scholarly reference resolution",
                        listOf("Riley Example", "Jordan Researcher"),
                        2024,
                        "10.5555/papertrail.fixture.reference-resolution.2024",
                        "JOURNAL_ARTICLE",
                    ),
                    ParsedBibliographyEntry(1, "ref2", "Reference two", "Reference two", emptyList(), 2019, null, "OTHER"),
                    ParsedBibliographyEntry(2, "ref3", "Reference three", "Reference three", emptyList(), 2018, null, "OTHER"),
                    ParsedBibliographyEntry(3, "ref4", "An unrelated ocean chemistry paper", "An unrelated ocean chemistry paper", listOf("Different Author"), 1991, null, "JOURNAL_ARTICLE"),
                ),
                rawParserOutput = "<TEI>test GROBID output</TEI>".toByteArray(),
            )
        }
    }

    private fun createQueuedRun(
        createdAt: Instant = Instant.now(),
        configurationJson: String = objectMapper.writeValueAsString(configurationFactory().from(RunConfigurationRequest())),
    ): CreatedRunIds {
        val bytes = "integration pdf bytes".toByteArray()
        val hash = expectedSha256(bytes)
        val documentId = UUID.randomUUID()
        val runId = UUID.randomUUID()
        val eventId = UUID.randomUUID()
        val correlationId = UUID.randomUUID()
        val objectKey = "source/$documentId/$hash.pdf"
        objectStore.put(objectKey, bytes)
        jdbc.update(
            """INSERT INTO source_documents (id, filename, content_type, object_key, sha256, language, page_count, extracted_character_count, parser_id, parser_version, created_at)
               VALUES (?, 'paper.pdf', 'application/pdf', ?, ?, 'en', 1, 1000, 'pdfbox', '3.0.5', ?)""",
            documentId, objectKey, hash, Timestamp.from(createdAt),
        )
        jdbc.update(
            """INSERT INTO analysis_runs (id, document_id, source_content_sha256, source_parser_id, source_parser_version, configuration_snapshot, status, progress, created_at)
               VALUES (?, ?, ?, 'grobid', '0.9.1-crf', ?::jsonb, 'QUEUED', '{"stage":"QUEUED"}'::jsonb, ?)""",
            runId, documentId, hash, configurationJson, Timestamp.from(createdAt),
        )
        val captureRequested = objectMapper.readTree(configurationJson).path("captureExecution").asBoolean(true)
        jdbc.update(
            "INSERT INTO analysis_run_execution (analysis_run_id, trace_id, capture_requested, capture_enabled, recording_state, completeness, started_at) VALUES (?, ?, ?, ?, 'RECORDING', 'RECORDING', ?)",
            runId, runId, captureRequested, captureRequested, Timestamp.from(createdAt),
        )
        val event = PipelineEvent(
            eventId,
            DOCUMENT_ANALYSIS_REQUESTED,
            1,
            runId,
            correlationId,
            null,
            createdAt,
            0,
            DocumentAnalysisRequestedPayload(documentId, hash),
        )
        jdbc.update(
            "INSERT INTO outbox_events (event_id, event_type, schema_version, analysis_run_id, correlation_id, occurred_at, payload, created_at) VALUES (?, ?, 1, ?, ?, ?, ?::jsonb, ?)",
            eventId,
            DOCUMENT_ANALYSIS_REQUESTED,
            runId,
            correlationId,
            Timestamp.from(createdAt),
            objectMapper.writeValueAsString(event),
            Timestamp.from(createdAt),
        )
        return CreatedRunIds(documentId, runId, eventId, hash)
    }

    private fun sourceDocumentDeletionService() = SourceDocumentDeletionService(
        jdbc = jdbc,
        transactionTemplate = TransactionTemplate(DataSourceTransactionManager(dataSource)),
        objectStore = objectStore,
    )

    private fun executionService() = AnalysisRunExecutionService(
        repository = AnalysisRunExecutionRepository(
            jdbc,
            TransactionTemplate(DataSourceTransactionManager(dataSource)),
            objectMapper,
        ),
        sanitizer = ExecutionCaptureSanitizer(),
        objectMapper = objectMapper,
    )

    private fun createQueuedRunForExistingDocument(documentId: UUID, hash: String): CreatedRunIds {
        val runId = UUID.randomUUID()
        val eventId = UUID.randomUUID()
        val correlationId = UUID.randomUUID()
        val createdAt = Instant.now()
        jdbc.update(
            """
            INSERT INTO analysis_runs (
                id, document_id, source_content_sha256, source_parser_id, source_parser_version,
                configuration_snapshot, status, progress, created_at
            ) VALUES (?, ?, ?, 'grobid', '0.9.1-crf', ?::jsonb, 'QUEUED', '{"stage":"QUEUED"}'::jsonb, ?)
            """.trimIndent(),
            runId,
            documentId,
            hash,
            objectMapper.writeValueAsString(configurationFactory().from(RunConfigurationRequest())),
            Timestamp.from(createdAt),
        )
        jdbc.update(
            "INSERT INTO analysis_run_execution (analysis_run_id, trace_id, capture_requested, capture_enabled, recording_state, completeness, started_at) VALUES (?, ?, TRUE, TRUE, 'RECORDING', 'RECORDING', ?)",
            runId, runId, Timestamp.from(createdAt),
        )
        val event = PipelineEvent(
            eventId,
            DOCUMENT_ANALYSIS_REQUESTED,
            1,
            runId,
            correlationId,
            null,
            createdAt,
            0,
            DocumentAnalysisRequestedPayload(documentId, hash),
        )
        jdbc.update(
            """
            INSERT INTO outbox_events (
                event_id, event_type, schema_version, analysis_run_id, correlation_id, occurred_at, payload, created_at
            ) VALUES (?, ?, 1, ?, ?, ?, ?::jsonb, ?)
            """.trimIndent(),
            eventId,
            DOCUMENT_ANALYSIS_REQUESTED,
            runId,
            correlationId,
            Timestamp.from(createdAt),
            objectMapper.writeValueAsString(event),
            Timestamp.from(createdAt),
        )
        return CreatedRunIds(documentId, runId, eventId, hash)
    }

    private fun analysisRunService(
        transactionTemplate: TransactionTemplate = TransactionTemplate(
            DataSourceTransactionManager(dataSource),
        ),
        providerCatalog: ProviderCatalog = ProviderCatalog.safeDefaults(),
    ): AnalysisRunService {
        val validator = PdfDocumentValidator(
            languageDetector = OptimaizeDocumentLanguageDetector(),
            maxBytes = 1_000_000,
            maxPages = 20,
            maxExtractedCharacters = 100_000,
            maxExtractedCharactersPerPage = 100_000,
            minimumExtractedCharacters = 100,
            minimumLanguageConfidence = 0.65,
            parserId = "pdfbox",
            parserVersion = "3.0.5",
        )
        val factory = configurationFactory(providerCatalog)
        return AnalysisRunService(
            jdbc,
            transactionTemplate,
            validator,
            objectStore,
            factory,
            ParsedDocumentRepository(jdbc, objectMapper),
            objectMapper,
            "grobid",
            "0.9.1-crf",
        )
    }

    private fun configurationFactory(
        providerCatalog: ProviderCatalog = ProviderCatalog.safeDefaults(),
        maxClaimCitationPairs: Int = ValidationLimitsSnapshot.DEFAULT_MAX_CLAIM_CITATION_PAIRS,
        systemOneAggregationEnabled: Boolean = false,
    ) = RunConfigurationFactory(
        objectMapper = objectMapper,
        providerCatalog = providerCatalog,
        parserId = "grobid",
        parserVersion = "0.9.1-crf",
        languageDetectorVersion = "0.6",
        limits = ValidationLimitsSnapshot(
            maxUploadBytes = 1_000_000,
            maxPages = 20,
            maxExtractedCharacters = 100_000,
            maxExtractedCharactersPerPage = 100_000,
            minimumExtractedCharacters = 100,
            minimumLanguageConfidence = 0.65,
            maxClaimCitationPairs = maxClaimCitationPairs,
        ),
        evidenceAggregationThresholds = TestEvidenceAggregationThresholds.values,
        systemOneAggregationEnabled = systemOneAggregationEnabled,
    )

    private fun claimReferenceVerificationRepository(): ClaimReferenceVerificationRepository = JdbcClaimReferenceVerificationRepository(jdbc)

    private fun humanReviewRepository(): HumanReviewRepository = JdbcHumanReviewRepository(jdbc)

    private fun humanReviewService(): HumanReviewService = HumanReviewService(humanReviewRepository())

    private fun referenceResolutionService(
        lookupFactories: List<ScholarlyMetadataLookupFactory> = listOf(RecordedFixtureScholarlyMetadataLookupFactory(objectMapper)),
    ) = ReferenceResolutionService(
        jdbc,
        objectMapper,
        TransactionTemplate(DataSourceTransactionManager(dataSource)),
        claimReferenceVerificationRepository(),
        ReferenceResolutionRepository(jdbc, objectMapper),
        CitedPaperAccessRepository(jdbc, objectMapper),
        EvidenceCoverageReportRepository(
            jdbc,
            EvidenceReportRepository(
                jdbc,
                EvidencePassageSpanRepository(
                    jdbc,
                    objectMapper,
                    TransactionTemplate(DataSourceTransactionManager(dataSource)),
                ),
            ),
            humanReviewRepository(),
        ),
        lookupFactories,
    )

    private fun stageCompletionService(
        resolutionService: ReferenceResolutionService = referenceResolutionService(),
    ) = AnalysisRunStageCompletionService(
        jdbc,
        TransactionTemplate(DataSourceTransactionManager(dataSource)),
        ParsedDocumentRepository(jdbc, objectMapper),
        resolutionService,
    )

    private fun controlledOpenAccessFactory(
        discovery: OpenAccessDiscovery?,
        fullText: String?,
        fetchCalls: AtomicInteger,
        onlyDoi: String? = null,
    ): OpenAccessProviderFactory = object : OpenAccessProviderFactory {
        override val providerId = "recorded-fixtures"

        override fun forRun(configuration: AnalysisConfigurationSnapshot): OpenAccessProvider = object : OpenAccessProvider {
            override fun discover(reference: BibliographyReference): OpenAccessDiscovery? =
                discovery.takeIf { onlyDoi == null || reference.doi == onlyDoi }

            override fun fetch(location: OpenAccessLocation): AcquiredFullText {
                fetchCalls.incrementAndGet()
                return AcquiredFullText(
                    bytes = requireNotNull(fullText) { "Unexpected full-text fetch for a controlled access result." }.toByteArray(Charsets.UTF_8),
                    mediaType = "text/plain",
                    location = location,
                )
            }
        }
    }

    private fun citedPaperAccessService(
        providerFactories: List<OpenAccessProviderFactory> = listOf(RecordedFixtureOpenAccessProviderFactory(
            objectMapper,
            ProviderCallGate(ProviderCatalog.safeDefaults()),
        )),
        languageDetector: DocumentLanguageDetector = OptimaizeDocumentLanguageDetector(),
    ) = CitedPaperAccessService(
        jdbc = jdbc,
        objectMapper = objectMapper,
        transactionTemplate = TransactionTemplate(DataSourceTransactionManager(dataSource)),
        claimReferenceVerificationRepository = claimReferenceVerificationRepository(),
        repository = CitedPaperAccessRepository(jdbc, objectMapper),
        objectStore = objectStore,
        languageDetector = languageDetector,
        textExtractor = PdfBoxCitedPaperTextExtractor(5_000_000),
        providerFactories = providerFactories,
    )

    private fun citedPaperAccessEventHandler(
        resolutionService: ReferenceResolutionService = referenceResolutionService(),
        providerFactories: List<OpenAccessProviderFactory> = listOf(RecordedFixtureOpenAccessProviderFactory(
            objectMapper,
            ProviderCallGate(ProviderCatalog.safeDefaults()),
        )),
        languageDetector: DocumentLanguageDetector = OptimaizeDocumentLanguageDetector(),
    ) = CitedPaperAcquisitionRequestedHandler(
        jdbc,
        TransactionTemplate(DataSourceTransactionManager(dataSource)),
        objectMapper,
        citedPaperAccessService(providerFactories, languageDetector),
        CitedPaperIndexingQueue(jdbc, objectMapper),
        stageCompletionService(resolutionService),
        executionService = executionService(),
    )

    private fun citedPaperIndexingEventHandler(
        resolutionService: ReferenceResolutionService = referenceResolutionService(),
        parser: CitedPaperParser = DefaultCitedPaperParser(emptyList()),
        systemOneProvider: SystemOneProvider = MockSystemOneProvider(),
        embeddingProviders: List<EmbeddingProvider> = listOf(FeatureHashEmbeddingProvider()),
        providerCatalog: ProviderCatalog = ProviderCatalog.safeDefaults(),
    ): CitedPaperIndexingRequestedHandler {
        val repository = EvidenceRetrievalRepository(
            jdbc,
            objectMapper,
            TransactionTemplate(DataSourceTransactionManager(dataSource)),
            PostgresHybridEvidenceRetriever(jdbc, ReciprocalRankFusion()),
        )
        val retrievalService = EvidenceRetrievalService(
            objectStore,
            parser,
            SectionAwareEvidenceChunker(),
            embeddingProviders,
            repository,
        )
        return CitedPaperIndexingRequestedHandler(
            jdbc,
            TransactionTemplate(DataSourceTransactionManager(dataSource)),
            objectMapper,
            retrievalService,
            repository,
            EvidenceVerificationService(
                jdbc,
                objectMapper,
                ProviderCallGate(providerCatalog),
                listOf(systemOneProvider),
                EvidenceJudgementRepository(jdbc, objectMapper, TransactionTemplate(DataSourceTransactionManager(dataSource))),
                EvidencePassageSpanRepository(jdbc, objectMapper, TransactionTemplate(DataSourceTransactionManager(dataSource))),
                LayaEvidencePassageSpanPlanner(),
                claimReferenceVerificationRepository(),
                executionService = executionService(),
            ),
            stageCompletionService(resolutionService),
            executionService = executionService(),
        )
    }

    private fun referenceResolutionEventHandler(
        resolutionService: ReferenceResolutionService = referenceResolutionService(),
    ) = ReferenceResolutionRequestedHandler(
        jdbc,
        TransactionTemplate(DataSourceTransactionManager(dataSource)),
        objectMapper,
        resolutionService,
        stageCompletionService(resolutionService),
        executionService = executionService(),
    )

    private fun processReferenceResolutionEvents(
        analysisRunId: UUID,
        providerFactories: List<OpenAccessProviderFactory> = listOf(RecordedFixtureOpenAccessProviderFactory(
            objectMapper,
            ProviderCallGate(ProviderCatalog.safeDefaults()),
        )),
        languageDetector: DocumentLanguageDetector = OptimaizeDocumentLanguageDetector(),
        citedPaperParser: CitedPaperParser = DefaultCitedPaperParser(emptyList()),
        resolutionService: ReferenceResolutionService = referenceResolutionService(),
        embeddingProviders: List<EmbeddingProvider> = listOf(FeatureHashEmbeddingProvider()),
        systemOneProvider: SystemOneProvider = MockSystemOneProvider(),
        providerCatalog: ProviderCatalog = ProviderCatalog.safeDefaults(),
        retryIndexingOnce: Boolean = false,
    ) {
        val events = jdbc.query(
            "SELECT payload::text FROM outbox_events WHERE analysis_run_id = ? AND event_type = ? ORDER BY created_at, event_id",
            { rs, _ -> rs.getString(1) },
            analysisRunId,
            REFERENCE_RESOLUTION_REQUESTED,
        )
        val handler = referenceResolutionEventHandler(resolutionService)
        events.forEach(handler::handle)
        processCitedPaperAcquisitionEvents(
            analysisRunId,
            providerFactories,
            languageDetector,
            citedPaperParser,
            resolutionService,
            embeddingProviders,
            systemOneProvider,
            providerCatalog,
            retryIndexingOnce,
        )
    }

    private fun processCitedPaperAcquisitionEvents(
        analysisRunId: UUID,
        providerFactories: List<OpenAccessProviderFactory>,
        languageDetector: DocumentLanguageDetector,
        citedPaperParser: CitedPaperParser = DefaultCitedPaperParser(emptyList()),
        resolutionService: ReferenceResolutionService = referenceResolutionService(),
        embeddingProviders: List<EmbeddingProvider> = listOf(FeatureHashEmbeddingProvider()),
        systemOneProvider: SystemOneProvider = MockSystemOneProvider(),
        providerCatalog: ProviderCatalog = ProviderCatalog.safeDefaults(),
        retryIndexingOnce: Boolean = false,
    ) {
        val events = jdbc.query(
            "SELECT payload::text FROM outbox_events WHERE analysis_run_id = ? AND event_type = ? ORDER BY created_at, event_id",
            { rs, _ -> rs.getString(1) },
            analysisRunId,
            CITED_PAPER_ACQUISITION_REQUESTED,
        )
        val handler = citedPaperAccessEventHandler(
            resolutionService = resolutionService,
            providerFactories = providerFactories,
            languageDetector = languageDetector,
        )
        events.forEach(handler::handle)
        val indexingEvents = jdbc.query(
            "SELECT payload::text FROM outbox_events WHERE analysis_run_id = ? AND event_type = ? ORDER BY created_at, event_id",
            { rs, _ -> rs.getString(1) },
            analysisRunId,
            CITED_PAPER_INDEXING_REQUESTED,
        )
        val indexingHandler = citedPaperIndexingEventHandler(
            resolutionService = resolutionService,
            parser = citedPaperParser,
            systemOneProvider = systemOneProvider,
            embeddingProviders = embeddingProviders,
            providerCatalog = providerCatalog,
        )
        var retriedAfterProviderFailure = false
        indexingEvents.forEach { event ->
            if (!retryIndexingOnce || retriedAfterProviderFailure) {
                indexingHandler.handle(event)
            } else {
                val failure = runCatching { indexingHandler.handle(event) }.exceptionOrNull()
                if (failure != null) {
                    assertEquals("Temporary test provider failure.", failure.message)
                    retriedAfterProviderFailure = true
                    indexingHandler.handle(event)
                }
            }
        }
        if (retryIndexingOnce) assertTrue(retriedAfterProviderFailure)
    }

    private fun issueSevenConfigurationJson(): String {
        val configuration = objectMapper.readTree(objectMapper.writeValueAsString(configurationFactory().from(RunConfigurationRequest()))) as ObjectNode
        configuration.remove(listOf("openAccess", "openAccessProviderConfigurationFingerprint", "openAccessRetentionDisclosure"))
        (configuration.get("claimExtractor") as ObjectNode).remove(
            listOf("targetSelectionPolicyVersion", "promptVersion", "outputMappingVersion", "retentionDisclosure"),
        )
        return configuration.toString()
    }

    private fun legacyResolutionConfigurationJson(): String {
        val configuration = objectMapper.readTree(objectMapper.writeValueAsString(configurationFactory().from(RunConfigurationRequest()))) as ObjectNode
        configuration.set<JsonNode>(
            "referenceResolution",
            objectMapper.readTree("""{"executionStatus":"NOT_RUN","scorePolicyVersion":null,"confidenceThreshold":null}"""),
        )
        configuration.remove("aggregation")
        (configuration.get("validationLimits") as ObjectNode).remove("maxClaimCitationPairs")
        return configuration.toString()
    }

    private fun eventHandler(
        parser: ScientificDocumentParser = TestScientificDocumentParser,
        resolutionService: ReferenceResolutionService = referenceResolutionService(),
        transactionTemplate: TransactionTemplate = TransactionTemplate(DataSourceTransactionManager(dataSource)),
        claimAnalysisProviders: List<ClaimAnalysisProvider> = listOf(HeuristicClaimAnalysisProvider(HeuristicClaimExtractor())),
        providerCatalog: ProviderCatalog = ProviderCatalog.safeDefaults(),
    ): DocumentAnalysisRequestedHandler {
        val processingService = AnalysisRunProcessingService(
            jdbc,
            transactionTemplate,
            objectMapper,
            objectStore,
            parser,
            ParsedDocumentRepository(jdbc, objectMapper),
            ClaimAnalysisService(
                providerCatalog,
                claimAnalysisProviders,
                ClaimAnalysisRequestFactory(),
            ),
            claimReferenceVerificationRepository(),
            resolutionService,
            stageCompletionService(resolutionService),
            executionService = executionService(),
        )
        return DocumentAnalysisRequestedHandler(objectMapper, processingService)
    }

    private fun englishPdf(): ByteArray {
        val text = ENGLISH.repeat(50)
        PDDocument().use { pdf ->
            val page = PDPage(PDRectangle.LETTER)
            pdf.addPage(page)
            PDPageContentStream(pdf, page).use { stream ->
                stream.beginText()
                stream.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 9f)
                stream.newLineAtOffset(35f, 740f)
                stream.showText(text)
                stream.endText()
            }
            return ByteArrayOutputStream().use { output -> pdf.save(output); output.toByteArray() }
        }
    }

    private fun expectedSha256(content: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(content)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun envelopeEventId(envelope: String): String = objectMapper.readTree(envelope).get("eventId").asText()

    private data class CreatedRunIds(val documentId: UUID, val analysisRunId: UUID, val eventId: UUID, val hash: String)

    private class MemoryObjectStore : SourceDocumentObjectStore {
        private val content = mutableMapOf<String, ByteArray>()
        private val contentTypes = mutableMapOf<String, String>()
        private val checksums = mutableMapOf<String, String>()
        private val reads = AtomicInteger()
        override fun put(objectKey: String, content: ByteArray, contentType: String) {
            this.content[objectKey] = content.copyOf()
            contentTypes[objectKey] = contentType
            checksums[objectKey] = MessageDigest.getInstance("SHA-256")
                .digest(content)
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }
        override fun get(objectKey: String): ByteArray {
            reads.incrementAndGet()
            return content[objectKey]?.copyOf() ?: error("Source object missing")
        }
        fun getCalls(): Int = reads.get()
        override fun stat(objectKey: String): SourceObjectMetadata {
            val value = content[objectKey] ?: error("Source object missing")
            return SourceObjectMetadata(size = value.size.toLong(), sha256 = checksums[objectKey])
        }
        override fun presignGet(objectKey: String, responseContentDisposition: String, expirySeconds: Int): String =
            "http://s3.test/source-documents/$objectKey?disposition=$responseContentDisposition&expires=$expirySeconds"
        override fun delete(objectKey: String) { content.remove(objectKey); contentTypes.remove(objectKey); checksums.remove(objectKey) }
        fun contentType(objectKey: String): String? = contentTypes[objectKey]
        fun contains(objectKey: String): Boolean = objectKey in content
        fun clear() { content.clear(); contentTypes.clear(); checksums.clear(); reads.set(0) }
    }

    @BeforeEach
    fun resetDatabases() {
        jdbc.update("TRUNCATE inbox_events, outbox_events, analysis_runs, source_documents CASCADE")
        redisTemplate.connectionFactory!!.connection.serverCommands().flushDb()
        objectStore.clear()
    }

    companion object {
        private const val ENGLISH = "This academic research study examines evidence and explains the results of scientific analysis. "
        private val postgres = PostgreSQLContainer<Nothing>(DockerImageName.parse("pgvector/pgvector:0.8.0-pg17"))
        private val redisContainer = GenericContainer<Nothing>(DockerImageName.parse("redis:7.4.2-alpine")).apply {
            withExposedPorts(6379)
        }

        @Container
        @JvmStatic
        val postgresContainer: PostgreSQLContainer<Nothing> = postgres

        @Container
        @JvmStatic
        val redisService: GenericContainer<Nothing> = redisContainer

        private lateinit var dataSource: DriverManagerDataSource
        private lateinit var jdbc: JdbcTemplate
        private lateinit var redisConnectionFactory: LettuceConnectionFactory
        private lateinit var redisTemplate: StringRedisTemplate
        private val objectMapper: ObjectMapper = jacksonObjectMapper().registerKotlinModule().registerModule(JavaTimeModule())
        private val objectStore = MemoryObjectStore()

        @BeforeAll
        @JvmStatic
        fun setUpInfrastructure() {
            dataSource = DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            jdbc = JdbcTemplate(dataSource)
            val migrationDirectory = listOf(Path.of("db/deploy"), Path.of("api/db/deploy"))
                .firstOrNull(Files::isDirectory) ?: error("Could not locate Sqitch deployment directory")
            listOf("extensions.sql", "core_documents.sql", "parsed_citation_structure.sql", "grobid_raw_output.sql", "analysis_run_listing_cursor.sql", "atomic_claims.sql", "conservative_reference_resolution.sql", "cited_paper_access.sql", "traceable_evidence_retrieval.sql").forEach { filename ->
                val migration = migrationDirectory.resolve(filename)
                dataSource.connection.use { connection ->
                    connection.createStatement().use { statement -> statement.execute(Files.readString(migration)) }
                }
            }
            val claimMigrationVerification = migrationDirectory.resolveSibling("verify").resolve("atomic_claims.sql")
            dataSource.connection.use { connection ->
                connection.createStatement().use { statement -> statement.execute(Files.readString(claimMigrationVerification)) }
            }
            val referenceResolutionMigrationVerification = migrationDirectory.resolveSibling("verify").resolve("conservative_reference_resolution.sql")
            dataSource.connection.use { connection ->
                connection.createStatement().use { statement -> statement.execute(Files.readString(referenceResolutionMigrationVerification)) }
            }
            val citedPaperAccessMigrationVerification = migrationDirectory.resolveSibling("verify").resolve("cited_paper_access.sql")
            dataSource.connection.use { connection ->
                connection.createStatement().use { statement -> statement.execute(Files.readString(citedPaperAccessMigrationVerification)) }
            }
            val evidenceRetrievalMigrationVerification = migrationDirectory.resolveSibling("verify").resolve("traceable_evidence_retrieval.sql")
            dataSource.connection.use { connection ->
                connection.createStatement().use { statement -> statement.execute(Files.readString(evidenceRetrievalMigrationVerification)) }
            }
            val evidenceCoverageMigration = migrationDirectory.resolve("conflict_aware_evidence_coverage.sql")
            dataSource.connection.use { connection ->
                connection.createStatement().use { statement -> statement.execute(Files.readString(evidenceCoverageMigration)) }
            }
            val evidenceCoverageMigrationVerification = migrationDirectory.resolveSibling("verify").resolve("conflict_aware_evidence_coverage.sql")
            dataSource.connection.use { connection ->
                connection.createStatement().use { statement -> statement.execute(Files.readString(evidenceCoverageMigrationVerification)) }
            }
            val humanReviewsMigration = migrationDirectory.resolve("human_reviews.sql")
            dataSource.connection.use { connection ->
                connection.createStatement().use { statement -> statement.execute(Files.readString(humanReviewsMigration)) }
            }
            val humanReviewsMigrationVerification = migrationDirectory.resolveSibling("verify").resolve("human_reviews.sql")
            dataSource.connection.use { connection ->
                connection.createStatement().use { statement -> statement.execute(Files.readString(humanReviewsMigrationVerification)) }
            }
            val sourceDocumentDeletionMigration = migrationDirectory.resolve("source_document_deletion.sql")
            dataSource.connection.use { connection ->
                connection.createStatement().use { statement -> statement.execute(Files.readString(sourceDocumentDeletionMigration)) }
            }
            val sourceDocumentDeletionMigrationVerification = migrationDirectory.resolveSibling("verify").resolve("source_document_deletion.sql")
            dataSource.connection.use { connection ->
                connection.createStatement().use { statement -> statement.execute(Files.readString(sourceDocumentDeletionMigrationVerification)) }
            }
            val layaEvidencePassageSpansMigration = migrationDirectory.resolve("laya_evidence_passage_spans.sql")
            dataSource.connection.use { connection ->
                connection.createStatement().use { statement -> statement.execute(Files.readString(layaEvidencePassageSpansMigration)) }
            }
            val layaEvidencePassageSpansMigrationVerification = migrationDirectory.resolveSibling("verify").resolve("laya_evidence_passage_spans.sql")
            dataSource.connection.use { connection ->
                connection.createStatement().use { statement -> statement.execute(Files.readString(layaEvidencePassageSpansMigrationVerification)) }
            }
            val pipelineProgressMigration = migrationDirectory.resolve("persisted_analysis_run_pipeline.sql")
            dataSource.connection.use { connection ->
                connection.createStatement().use { statement -> statement.execute(Files.readString(pipelineProgressMigration)) }
            }
            val pipelineProgressMigrationVerification = migrationDirectory.resolveSibling("verify").resolve("persisted_analysis_run_pipeline.sql")
            dataSource.connection.use { connection ->
                connection.createStatement().use { statement -> statement.execute(Files.readString(pipelineProgressMigrationVerification)) }
            }
            val executionMigration = migrationDirectory.resolve("analysis_run_execution.sql")
            dataSource.connection.use { connection ->
                connection.createStatement().use { statement -> statement.execute(Files.readString(executionMigration)) }
            }
            val executionMigrationVerification = migrationDirectory.resolveSibling("verify").resolve("analysis_run_execution.sql")
            dataSource.connection.use { connection ->
                connection.createStatement().use { statement -> statement.execute(Files.readString(executionMigrationVerification)) }
            }

            val redisConfiguration = RedisStandaloneConfiguration(redisService.host, redisService.getMappedPort(6379))
            redisConnectionFactory = LettuceConnectionFactory(redisConfiguration)
            redisConnectionFactory.afterPropertiesSet()
            redisTemplate = StringRedisTemplate(redisConnectionFactory)
            redisTemplate.afterPropertiesSet()
        }

        @AfterAll
        @JvmStatic
        fun closeInfrastructure() {
            if (::redisConnectionFactory.isInitialized) redisConnectionFactory.destroy()
        }

        private val redis: StringRedisTemplate
            get() = redisTemplate
    }
}
