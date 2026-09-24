package com.papertrail.api.queue

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.papertrail.api.documents.OptimaizeDocumentLanguageDetector
import com.papertrail.api.documents.PdfDocumentValidator
import com.papertrail.api.runs.AnalysisRunService
import com.papertrail.api.runs.RunConfigurationFactory
import com.papertrail.api.runs.RunConfigurationRequest
import com.papertrail.api.runs.ValidationLimitsSnapshot
import com.papertrail.api.storage.SourceDocumentObjectStore
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
import org.springframework.jdbc.core.JdbcTemplate
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
import java.time.Duration
import java.time.Instant
import java.util.UUID

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
            "NOT_RUN",
            jdbc.queryForObject(
                "SELECT configuration_snapshot #>> '{referenceResolution,executionStatus}' FROM analysis_runs WHERE id = ?",
                String::class.java,
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
        assertThrows(org.springframework.dao.DataAccessException::class.java) {
            jdbc.update("UPDATE analysis_runs SET configuration_snapshot = '{}'::jsonb WHERE id = ?", created.analysisRunId)
        }
        assertThrows(org.springframework.dao.DataAccessException::class.java) {
            jdbc.update("UPDATE analysis_runs SET status = 'COMPLETED' WHERE id = ?", secondRun.analysisRunId)
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

        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM inbox_events WHERE event_id = (SELECT event_id FROM outbox_events WHERE analysis_run_id = ?)", Int::class.java, created.analysisRunId))
        assertEquals("PROCESSING", jdbc.queryForObject("SELECT status FROM analysis_runs WHERE id = ?", String::class.java, created.analysisRunId))
        assertEquals(
            "SOURCE_DOCUMENT_VERIFIED",
            jdbc.queryForObject("SELECT progress ->> 'stage' FROM analysis_runs WHERE id = ?", String::class.java, created.analysisRunId),
        )
        assertEquals(0L, operations.pending(stream, group)?.totalPendingMessages ?: 0L)
        val processedEvent: PipelineEvent<DocumentAnalysisRequestedPayload> = objectMapper.readValue(envelope)
        handler.markFailed(processedEvent, "A stale retry must not overwrite a committed success.")
        assertEquals("PROCESSING", jdbc.queryForObject("SELECT status FROM analysis_runs WHERE id = ?", String::class.java, created.analysisRunId))
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

        assertEquals("PROCESSING", jdbc.queryForObject("SELECT status FROM analysis_runs WHERE id = ?", String::class.java, created.analysisRunId))
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

    private fun createQueuedRun(): CreatedRunIds {
        val bytes = "integration pdf bytes".toByteArray()
        val hash = expectedSha256(bytes)
        val documentId = UUID.randomUUID()
        val runId = UUID.randomUUID()
        val eventId = UUID.randomUUID()
        val correlationId = UUID.randomUUID()
        val objectKey = "source/$documentId/$hash.pdf"
        objectStore.put(objectKey, bytes)
        val createdAt = Instant.now()
        jdbc.update(
            """INSERT INTO source_documents (id, filename, content_type, object_key, sha256, language, page_count, extracted_character_count, parser_id, parser_version, created_at)
               VALUES (?, 'paper.pdf', 'application/pdf', ?, ?, 'en', 1, 1000, 'pdfbox', '3.0.5', ?)""",
            documentId, objectKey, hash, java.sql.Timestamp.from(createdAt),
        )
        jdbc.update(
            """INSERT INTO analysis_runs (id, document_id, source_content_sha256, source_parser_id, source_parser_version, configuration_snapshot, status, progress, created_at)
               VALUES (?, ?, ?, 'pdfbox', '3.0.5', '{"claimExtractor":{"provider":"heuristic"}}'::jsonb, 'QUEUED', '{"stage":"QUEUED"}'::jsonb, ?)""",
            runId, documentId, hash, java.sql.Timestamp.from(createdAt),
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
            java.sql.Timestamp.from(createdAt),
            objectMapper.writeValueAsString(event),
            java.sql.Timestamp.from(createdAt),
        )
        return CreatedRunIds(documentId, runId, eventId, hash)
    }

    private fun analysisRunService(
        transactionTemplate: TransactionTemplate = TransactionTemplate(
            org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource),
        ),
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
        val factory = RunConfigurationFactory(
            objectMapper,
            "3.0.5",
            "0.6",
            ValidationLimitsSnapshot(1_000_000, 20, 100_000, 100_000, 100, 0.65),
        )
        return AnalysisRunService(
            jdbc,
            transactionTemplate,
            validator,
            objectStore,
            factory,
            objectMapper,
            "pdfbox",
            "3.0.5",
        )
    }

    private fun eventHandler() = DocumentAnalysisRequestedHandler(
        jdbc,
        TransactionTemplate(org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource)),
        objectMapper,
        objectStore,
    )

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

    private fun expectedSha256(content: ByteArray): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(content)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun envelopeEventId(envelope: String): String = objectMapper.readTree(envelope).get("eventId").asText()

    private data class CreatedRunIds(val documentId: UUID, val analysisRunId: UUID, val eventId: UUID, val hash: String)

    private class MemoryObjectStore : SourceDocumentObjectStore {
        private val content = mutableMapOf<String, ByteArray>()
        override fun put(objectKey: String, content: ByteArray) { this.content[objectKey] = content.copyOf() }
        override fun get(objectKey: String): ByteArray = content[objectKey]?.copyOf() ?: error("Source object missing")
        override fun delete(objectKey: String) { content.remove(objectKey) }
        fun clear() { content.clear() }
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
            listOf("extensions.sql", "core_documents.sql").forEach { filename ->
                val migration = migrationDirectory.resolve(filename)
                dataSource.connection.use { connection ->
                    connection.createStatement().use { statement -> statement.execute(Files.readString(migration)) }
                }
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
