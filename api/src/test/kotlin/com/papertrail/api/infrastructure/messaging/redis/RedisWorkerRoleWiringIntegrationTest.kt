package com.papertrail.api.infrastructure.messaging.redis

import com.fasterxml.jackson.databind.ObjectMapper
import com.papertrail.api.analysis.queue.DocumentAnalysisRequestedHandler
import com.papertrail.api.evidence.queue.CitedPaperIndexingRequestedHandler
import com.papertrail.api.infrastructure.messaging.outbox.OutboxPublisher
import com.papertrail.api.scholarly.acquisition.queue.CitedPaperAcquisitionRequestedHandler
import com.papertrail.api.scholarly.references.queue.ReferenceResolutionRequestedHandler
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.data.domain.Range
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName

@Testcontainers
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = [
        "paper-trail.role=worker",
        "paper-trail.queue.poll-delay-ms=60000",
        "spring.data.redis.password=",
        "paper-trail.providers.openai-compatible-chat.enabled=false",
        "paper-trail.providers.crossref.enabled=false",
        "paper-trail.providers.unpaywall.enabled=false",
        "paper-trail.providers.ollama.enabled=false",
        "paper-trail.providers.laya.enabled=false",
    ],
)
class RedisWorkerRoleWiringIntegrationTest {
    @Autowired
    private lateinit var applicationContext: ApplicationContext

    @Autowired
    private lateinit var redis: StringRedisTemplate

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var worker: RedisStreamWorker

    @Value("\${paper-trail.queue.stream}")
    private lateinit var stream: String

    @Value("\${paper-trail.queue.group}")
    private lateinit var group: String

    @Test
    fun `worker role discovers handlers and connects to Redis without the API outbox`() {
        assertNotNull(applicationContext.getBean(RedisStreamWorker::class.java))
        assertNotNull(applicationContext.getBean(DocumentAnalysisRequestedHandler::class.java))
        assertNotNull(applicationContext.getBean(ReferenceResolutionRequestedHandler::class.java))
        assertNotNull(applicationContext.getBean(CitedPaperAcquisitionRequestedHandler::class.java))
        assertNotNull(applicationContext.getBean(CitedPaperIndexingRequestedHandler::class.java))
        assertFalse(applicationContext.getBeansOfType(OutboxPublisher::class.java).isNotEmpty())

        assertEquals("PONG", redis.connectionFactory!!.connection.ping())
    }

    @Test
    fun `worker role creates its consumer group and dead-letters a fixture envelope`() {
        val eventId = "00000000-0000-0000-0000-000000000001"
        val serializedEvent = """{"eventId":"$eventId","eventType":"UnsupportedFixtureEvent","schemaVersion":1,"analysisRunId":"00000000-0000-0000-0000-000000000002","correlationId":"00000000-0000-0000-0000-000000000003","causationId":null,"occurredAt":"2026-10-06T00:00:00Z","attempt":0,"payload":{}}"""
        val messageId = requireNotNull(
            redis.opsForStream<String, String>().add(stream, mapOf("event" to serializedEvent)),
        )

        worker.poll()

        val deadLetter = redis.opsForStream<String, String>()
            .range("ae:dlq", Range.unbounded<String>())
            .orEmpty()
            .single()
        assertEquals(messageId.value, deadLetter.value["sourceMessageId"])
        assertEquals("UNSUPPORTED_EVENT_TYPE", deadLetter.value["errorCode"])
        assertEquals(eventId, objectMapper.readTree(deadLetter.value["event"]).path("eventId").asText())
        assertEquals(0L, redis.opsForStream<String, String>().pending(stream, group)?.totalPendingMessages)
    }

    companion object {
        private val postgres = PostgreSQLContainer<Nothing>(DockerImageName.parse("pgvector/pgvector:0.8.0-pg17"))
        private val redisContainer = GenericContainer<Nothing>(DockerImageName.parse("redis:7.4.2-alpine")).apply {
            withExposedPorts(6379)
            withCommand("redis-server", "--save", "", "--appendonly", "no")
        }

        @Container
        @JvmStatic
        val postgresContainer: PostgreSQLContainer<Nothing> = postgres

        @Container
        @JvmStatic
        val redisService: GenericContainer<Nothing> = redisContainer

        @JvmStatic
        @DynamicPropertySource
        fun runtimeProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
            registry.add("spring.data.redis.host", redisContainer::getHost)
            registry.add("spring.data.redis.port", redisContainer::getFirstMappedPort)
        }
    }
}
