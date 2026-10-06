package com.papertrail.api.infrastructure.http

import com.fasterxml.jackson.databind.ObjectMapper
import com.papertrail.api.infrastructure.messaging.outbox.OutboxPublisher
import com.papertrail.api.infrastructure.messaging.redis.RedisStreamWorker
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.context.ApplicationContext
import org.springframework.http.HttpStatus
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName

@Testcontainers
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "paper-trail.role=api",
        "paper-trail.queue.publish-delay-ms=60000",
        "paper-trail.providers.openai-compatible-chat.enabled=false",
        "paper-trail.providers.crossref.enabled=false",
        "paper-trail.providers.unpaywall.enabled=false",
        "paper-trail.providers.ollama.enabled=false",
        "paper-trail.providers.laya.enabled=false",
    ],
)
class ApiRoleHttpSmokeIntegrationTest {
    @Autowired
    private lateinit var applicationContext: ApplicationContext

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Test
    fun `api role serves health and OpenAPI over HTTP with only the API outbox`() {
        assertNotNull(applicationContext.getBean(OutboxPublisher::class.java))
        assertFalse(applicationContext.getBeansOfType(RedisStreamWorker::class.java).isNotEmpty())

        val health = restTemplate.getForEntity("/api/v1/health", String::class.java)
        assertEquals(HttpStatus.OK, health.statusCode)
        assertEquals("ok", objectMapper.readTree(health.body).path("status").asText())

        val apiDocs = restTemplate.getForEntity("/v3/api-docs", String::class.java)
        assertEquals(HttpStatus.OK, apiDocs.statusCode)
        val document = objectMapper.readTree(apiDocs.body)
        assertTrue(document.path("paths").has("/api/v1/providers"))
        assertTrue(document.path("paths").has("/api/v1/health"))
    }

    companion object {
        private val postgres = PostgreSQLContainer<Nothing>(DockerImageName.parse("pgvector/pgvector:0.8.0-pg17")).apply {
            withInitScript("runtime-role/outbox.sql")
        }

        @Container
        @JvmStatic
        val postgresContainer: PostgreSQLContainer<Nothing> = postgres

        @JvmStatic
        @DynamicPropertySource
        fun runtimeProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
        }
    }
}
