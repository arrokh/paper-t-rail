package com.papertrail.api.infrastructure.providers

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.data.redis.connection.RedisStandaloneConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.testcontainers.containers.GenericContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.test.assertIs

@Testcontainers
class RedisProviderEgressPermitGateIntegrationTest {
    private val connectionFactories = mutableListOf<LettuceConnectionFactory>()

    @BeforeEach
    fun resetRedis() {
        newRedisTemplate().execute { connection ->
            connection.serverCommands().flushDb()
        }
    }

    @AfterEach
    fun closeConnections() {
        connectionFactories.forEach(LettuceConnectionFactory::destroy)
        connectionFactories.clear()
    }

    @Test
    fun `independent clients share provider rate and concurrency while providers remain independent`() {
        val firstClient = RedisProviderEgressPermitGate(newRedisTemplate())
        val secondClient = RedisProviderEgressPermitGate(newRedisTemplate())
        val deadline = Instant.now().plusSeconds(5)
        val first = assertIs<ProviderEgressPermitGate.AcquireResult.Granted>(firstClient.acquire("crossref", deadline)).permit
        val otherProvider = assertIs<ProviderEgressPermitGate.AcquireResult.Granted>(secondClient.acquire("datacite", deadline)).permit
        assertEquals(ProviderEgressPermitGate.ReleaseResult.RELEASED, otherProvider.release())

        val executor = Executors.newSingleThreadExecutor()
        try {
            val startedAt = System.nanoTime()
            val waitingForCrossref = executor.submit<ProviderEgressPermitGate.AcquireResult> {
                secondClient.acquire("crossref", deadline)
            }
            assertThrows(TimeoutException::class.java) { waitingForCrossref.get(200, TimeUnit.MILLISECONDS) }
            assertEquals(ProviderEgressPermitGate.ReleaseResult.RELEASED, first.release())
            assertThrows(TimeoutException::class.java) { waitingForCrossref.get(600, TimeUnit.MILLISECONDS) }

            val second = assertIs<ProviderEgressPermitGate.AcquireResult.Granted>(waitingForCrossref.get(2, TimeUnit.SECONDS)).permit
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt) >= 800)
            assertEquals(ProviderEgressPermitGate.ReleaseResult.RELEASED, second.release())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `redis failure never grants provider egress`() {
        val unavailableFactory = LettuceConnectionFactory(RedisStandaloneConfiguration("127.0.0.1", 1)).apply {
            afterPropertiesSet()
            start()
        }
        connectionFactories.add(unavailableFactory)
        val unavailableRedis = StringRedisTemplate(unavailableFactory).apply { afterPropertiesSet() }
        val result = RedisProviderEgressPermitGate(unavailableRedis)
            .acquire("crossref", Instant.now().plusSeconds(1))

        assertEquals(ProviderEgressPermitGate.AcquireResult.Unavailable, result)
    }

    private fun newRedisTemplate(): StringRedisTemplate {
        val configuration = RedisStandaloneConfiguration(redisContainer.host, redisContainer.getMappedPort(6379))
        val connectionFactory = LettuceConnectionFactory(configuration).apply {
            afterPropertiesSet()
            start()
        }
        connectionFactories.add(connectionFactory)
        return StringRedisTemplate(connectionFactory).apply { afterPropertiesSet() }
    }

    companion object {
        private val redisContainer = GenericContainer<Nothing>(DockerImageName.parse("redis:7.4.2-alpine")).apply {
            withExposedPorts(6379)
            withCommand("redis-server", "--save", "", "--appendonly", "no")
        }

        @Container
        @JvmStatic
        val redisService: GenericContainer<Nothing> = redisContainer
    }
}
