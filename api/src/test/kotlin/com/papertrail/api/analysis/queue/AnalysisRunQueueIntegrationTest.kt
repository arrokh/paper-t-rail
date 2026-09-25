package com.papertrail.api.analysis.queue

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.papertrail.api.analysis.configuration.AnalysisConfigurationSnapshot
import com.papertrail.api.analysis.configuration.ExternalProviderConsentSnapshot
import com.papertrail.api.analysis.configuration.RunConfigurationFactory
import com.papertrail.api.analysis.configuration.ValidationLimitsSnapshot
import com.papertrail.api.analysis.http.CreatedAnalysisRunResponse
import com.papertrail.api.analysis.http.RunConfigurationRequest
import com.papertrail.api.analysis.service.AnalysisRunProcessingService
import com.papertrail.api.analysis.service.AnalysisRunService
import com.papertrail.api.analysis.service.AnalysisRunStageCompletionService
import com.papertrail.api.infrastructure.messaging.events.PipelineEvent
import com.papertrail.api.scholarly.references.queue.REFERENCE_RESOLUTION_REQUESTED
import com.papertrail.api.scholarly.references.queue.ReferenceResolutionRequestedPayload
import com.papertrail.api.infrastructure.messaging.outbox.OutboxPublisher
import com.papertrail.api.infrastructure.messaging.redis.RedisStreamWorker
import com.papertrail.api.scholarly.references.queue.ReferenceResolutionRequestedHandler
import com.papertrail.api.document.validation.OptimaizeDocumentLanguageDetector
import com.papertrail.api.citation.claims.service.ClaimExtractionService
import com.papertrail.api.citation.claims.service.HeuristicClaimExtractor
import com.papertrail.api.document.validation.PdfDocumentValidator
import com.papertrail.api.citation.parsing.ParsedBibliographyEntry
import com.papertrail.api.citation.parsing.ParsedCitationContext
import com.papertrail.api.citation.parsing.ParsedCitationOccurrence
import com.papertrail.api.citation.parsing.ParsedDocumentRepository
import com.papertrail.api.citation.parsing.ParsedScientificDocument
import com.papertrail.api.citation.parsing.ParsedSection
import com.papertrail.api.citation.parsing.ScientificDocumentParser
import com.papertrail.api.infrastructure.providers.ProviderCatalog
import com.papertrail.api.scholarly.references.client.BibliographyReference
import com.papertrail.api.scholarly.references.client.ScholarlyMetadataLookup
import com.papertrail.api.scholarly.references.client.ScholarlyMetadataLookupFactory
import com.papertrail.api.scholarly.references.client.ScholarlyWork
import com.papertrail.api.scholarly.references.service.RecordedFixtureScholarlyMetadataLookupFactory
import com.papertrail.api.scholarly.references.normalization.DoiNormalizer
import com.papertrail.api.scholarly.references.repository.ReferenceResolutionRepository
import com.papertrail.api.scholarly.references.service.ReferenceResolutionService
import com.papertrail.api.infrastructure.providers.reviewedExternalProviderCatalog
import com.papertrail.api.document.storage.SourceDocumentObjectStore
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
            0.9,
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
    fun `legacy immutable runs keep reference resolution not run rather than inventing a policy`() {
        val created = createQueuedRun(configurationJson = legacyResolutionConfigurationJson())
        val event = jdbc.queryForObject("SELECT payload::text FROM outbox_events WHERE analysis_run_id = ?", String::class.java, created.analysisRunId)

        eventHandler().handle(event!!)

        assertEquals("PARSED", jdbc.queryForObject("SELECT status FROM analysis_runs WHERE id = ?", String::class.java, created.analysisRunId))
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM bibliography_entry_resolutions WHERE analysis_run_id = ?", Int::class.java, created.analysisRunId))
        val report = referenceResolutionService().report(created.analysisRunId)!!
        assertEquals("NOT_RUN", report.referenceResolution.executionStatus)
        assertEquals(null, report.referenceResolution.scorePolicyVersion)
        assertEquals(null, report.referenceResolution.confidenceThreshold)
        assertEquals(4, report.referenceResolution.summary.notAttempted)
        assertTrue(report.referenceResolution.entries.all { it.canonicalPaper == null })
    }

    @Test
    fun `parsed document is withheld until processing reaches a ready terminal state`() {
        val created = createQueuedRun()
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

        val parsed = service.getParsedDocument(created.analysisRunId)
        assertEquals("grobid", parsed.parser.provider)
        assertEquals(created.hash, parsed.sourceContentSha256)
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
        val consent = ExternalProviderConsentSnapshot(
            "reviewed-llm",
            listOf("citation_context"),
        )
        val created = analysisRunService(providerCatalog = reviewedExternalProviderCatalog()).createFromUpload(
            "paper.pdf",
            "application/pdf",
            englishPdf(),
            RunConfigurationRequest(claimExtractorProvider = "reviewed-llm", externalProviderConsents = listOf(consent)),
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
            "reviewed-llm",
            jdbc.queryForObject(
                "SELECT configuration_snapshot #>> '{externalProviderConsents,0,providerId}' FROM analysis_runs WHERE id = ?",
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
        assertEquals("PARSED", jdbc.queryForObject("SELECT status FROM analysis_runs WHERE id = ?", String::class.java, created.analysisRunId))
        assertNull(jdbc.queryForObject("SELECT completed_at FROM analysis_runs WHERE id = ?", Timestamp::class.java, created.analysisRunId))
        assertEquals(
            "PARSED",
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
        assertEquals(0.9, report.referenceResolution.confidenceThreshold)
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
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM canonical_papers", Int::class.java))
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
        assertEquals("PARSED", jdbc.queryForObject("SELECT status FROM analysis_runs WHERE id = ?", String::class.java, created.analysisRunId))
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
        assertEquals("PARSED", jdbc.queryForObject("SELECT status FROM analysis_runs WHERE id = ?", String::class.java, created.analysisRunId))
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
        } finally {
            replacementWorker.shutdownLeaseHeartbeat()
        }
        assertEquals("PARSED", jdbc.queryForObject("SELECT status FROM analysis_runs WHERE id = ?", String::class.java, created.analysisRunId))
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

            assertEquals("PARSED", jdbc.queryForObject("SELECT status FROM analysis_runs WHERE id = ?", String::class.java, created.analysisRunId))
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

    private fun configurationFactory(providerCatalog: ProviderCatalog = ProviderCatalog.safeDefaults()) = RunConfigurationFactory(
        objectMapper = objectMapper,
        providerCatalog = providerCatalog,
        parserId = "grobid",
        parserVersion = "0.9.1-crf",
        languageDetectorVersion = "0.6",
        limits = ValidationLimitsSnapshot(1_000_000, 20, 100_000, 100_000, 100, 0.65),
    )

    private fun referenceResolutionService(
        lookupFactories: List<ScholarlyMetadataLookupFactory> = listOf(RecordedFixtureScholarlyMetadataLookupFactory(objectMapper)),
    ) = ReferenceResolutionService(
        jdbc,
        objectMapper,
        ReferenceResolutionRepository(jdbc, objectMapper, TransactionTemplate(DataSourceTransactionManager(dataSource))),
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

    private fun referenceResolutionEventHandler(
        resolutionService: ReferenceResolutionService = referenceResolutionService(),
    ) = ReferenceResolutionRequestedHandler(
        jdbc,
        TransactionTemplate(DataSourceTransactionManager(dataSource)),
        objectMapper,
        resolutionService,
        stageCompletionService(resolutionService),
    )

    private fun processReferenceResolutionEvents(analysisRunId: UUID) {
        val events = jdbc.query(
            "SELECT payload::text FROM outbox_events WHERE analysis_run_id = ? AND event_type = ? ORDER BY created_at, event_id",
            { rs, _ -> rs.getString(1) },
            analysisRunId,
            REFERENCE_RESOLUTION_REQUESTED,
        )
        val handler = referenceResolutionEventHandler()
        events.forEach(handler::handle)
    }

    private fun legacyResolutionConfigurationJson(): String {
        val configuration = objectMapper.readTree(objectMapper.writeValueAsString(configurationFactory().from(RunConfigurationRequest()))) as ObjectNode
        configuration.set<JsonNode>(
            "referenceResolution",
            objectMapper.readTree("""{"executionStatus":"NOT_RUN","scorePolicyVersion":null,"confidenceThreshold":null}"""),
        )
        return configuration.toString()
    }

    private fun eventHandler(
        parser: ScientificDocumentParser = TestScientificDocumentParser,
        resolutionService: ReferenceResolutionService = referenceResolutionService(),
        transactionTemplate: TransactionTemplate = TransactionTemplate(DataSourceTransactionManager(dataSource)),
    ): DocumentAnalysisRequestedHandler {
        val processingService = AnalysisRunProcessingService(
            jdbc,
            transactionTemplate,
            objectMapper,
            objectStore,
            parser,
            ParsedDocumentRepository(jdbc, objectMapper),
            ClaimExtractionService(ProviderCatalog.safeDefaults(), listOf(HeuristicClaimExtractor())),
            resolutionService,
            stageCompletionService(resolutionService),
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
        override fun put(objectKey: String, content: ByteArray, contentType: String) {
            this.content[objectKey] = content.copyOf()
            contentTypes[objectKey] = contentType
        }
        override fun get(objectKey: String): ByteArray = content[objectKey]?.copyOf() ?: error("Source object missing")
        override fun delete(objectKey: String) { content.remove(objectKey); contentTypes.remove(objectKey) }
        fun contentType(objectKey: String): String? = contentTypes[objectKey]
        fun clear() { content.clear(); contentTypes.clear() }
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
            listOf("extensions.sql", "core_documents.sql", "parsed_citation_structure.sql", "grobid_raw_output.sql", "analysis_run_listing_cursor.sql", "atomic_claims.sql", "conservative_reference_resolution.sql").forEach { filename ->
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
