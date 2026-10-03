package com.papertrail.api.scholarly.references.client

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.papertrail.api.analysis.configuration.AnalysisConfigurationSnapshot
import com.papertrail.api.analysis.configuration.RunConfigurationFactory
import com.papertrail.api.analysis.configuration.ValidationLimitsSnapshot
import com.papertrail.api.infrastructure.cache.RedisProviderCacheStore
import com.papertrail.api.analysis.http.RunConfigurationRequest
import com.papertrail.api.infrastructure.providers.DataCategory
import com.papertrail.api.infrastructure.providers.ProviderCallGate
import com.papertrail.api.infrastructure.providers.ProviderCallRejectedException
import com.papertrail.api.infrastructure.providers.ProviderCatalog
import com.papertrail.api.infrastructure.providers.externalProviderConsent
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.data.redis.connection.RedisStandaloneConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.ExpectedCount
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import org.testcontainers.containers.GenericContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.time.Duration
import java.util.concurrent.TimeUnit

@Testcontainers
class CrossrefLookupCacheIntegrationTest {
    @Test
    fun `DOI cache reuses normalized metadata without persisting provider response or contact email`() {
        val fixture = fixture(contactEmail = "operator@example.invalid")
        val (lookup, server) = lookup(fixture)
        server.expect(requestTo("https://api.crossref.org/works/10.1234/cache-hit?mailto=operator@example.invalid"))
            .andExpect(method(HttpMethod.GET))
            .andRespond(withSuccess(
                """{"message":{"DOI":"10.1234/Cache-Hit","title":["  Cached   study "],"author":[{"given":" Ada ","family":" Researcher "}],"issued":{"date-parts":[[2024]]}}}""",
                MediaType.APPLICATION_JSON,
            ))

        val first = lookup.byDoi("https://doi.org/10.1234/Cache-Hit")
        val second = lookup.byDoi("doi:10.1234/cache-hit")

        assertEquals(first, second)
        assertEquals(ScholarlyWork("10.1234/cache-hit", "Cached study", listOf("Ada Researcher"), 2024), second)
        server.verify()
        val cachedValue = requireNotNull(redis.opsForValue().get(redis.keys("crossref:doi:*").single()))
        assertTrue(cachedValue.contains("Cached study"))
        assertFalse(cachedValue.contains("message"))
        assertFalse(cachedValue.contains("operator@example.invalid"))
        assertEquals(setOf("doi", "title", "authors", "year"), objectMapper.readTree(cachedValue).single().fieldNames().asSequence().toSet())
        assertEquals(setOf("crossref:doi:v1:10.1234/cache-hit"), redis.keys("crossref:doi:*").toSet())
    }

    @Test
    fun `bibliographic search cache uses a deterministic hash of normalized query text`() {
        val fixture = fixture()
        val (lookup, server) = lookup(fixture)
        server.expect(requestTo("https://api.crossref.org/works?query.bibliographic=A%20test%20title%20Ada%20Researcher%202024&rows=10"))
            .andExpect(method(HttpMethod.GET))
            .andExpect(queryParam("query.bibliographic", "A%20test%20title%20Ada%20Researcher%202024"))
            .andRespond(withSuccess(
                """{"message":{"items":[{"DOI":"10.1234/search-result","title":["A test title"],"author":[{"given":"Ada","family":"Researcher"}],"issued":{"date-parts":[[2024]]}}]}}""",
                MediaType.APPLICATION_JSON,
            ))

        val first = lookup.search(BibliographyReference("A test title", listOf("Ada Researcher"), 2024, null, "JOURNAL_ARTICLE"))
        val second = lookup.search(BibliographyReference("  A   TEST title ", listOf("Ada   Researcher"), 2024, null, "JOURNAL_ARTICLE"))

        assertEquals(first, second)
        assertEquals(1, first.size)
        assertEquals(1, redis.keys("crossref:search:v1:*").size)
        assertTrue(redis.keys("crossref:search:v1:*").single().matches(Regex("crossref:search:v1:[0-9a-f]{64}")))
        val cachedValue = requireNotNull(redis.opsForValue().get(redis.keys("crossref:search:v1:*").single()))
        assertFalse(cachedValue.contains("Ada Researcher 2024"))
        server.verify()
    }

    @Test
    fun `negative DOI result is cached using the configured negative TTL`() {
        val fixture = fixture()
        val cache = cache(positiveTtl = Duration.ofDays(30), negativeTtl = Duration.ofSeconds(1))
        val (lookup, server) = lookup(fixture, cache)
        server.expect(requestTo("https://api.crossref.org/works/10.1234/missing"))
            .andRespond(withStatus(HttpStatus.NOT_FOUND))
        server.expect(requestTo("https://api.crossref.org/works?query.bibliographic=No%20matching%20work&rows=10"))
            .andRespond(withSuccess("""{"message":{"items":[]}}""", MediaType.APPLICATION_JSON))

        assertNull(lookup.byDoi("10.1234/missing"))
        assertNull(lookup.byDoi("10.1234/missing"))
        val emptySearch = BibliographyReference("No matching work", emptyList(), null, null, "JOURNAL_ARTICLE")
        assertTrue(lookup.search(emptySearch).isEmpty())
        assertTrue(lookup.search(emptySearch).isEmpty())

        val key = "crossref:doi:v1:10.1234/missing"
        assertTrue(redis.hasKey(key))
        assertTrue(requireNotNull(redis.getExpire(key, TimeUnit.SECONDS)) in 0L..1L)
        assertEquals(1, redis.keys("crossref:search:v1:*").size)
        server.verify()
    }

    @Test
    fun `expired positive result is fetched again from Crossref`() {
        val fixture = fixture()
        val cache = cache(positiveTtl = Duration.ofMillis(250), negativeTtl = Duration.ofSeconds(1))
        val (lookup, server) = lookup(fixture, cache)
        val response = """{"message":{"DOI":"10.1234/expiry","title":["Expiring study"],"author":[],"issued":{"date-parts":[[2024]]}}}"""
        server.expect(ExpectedCount.twice(), requestTo("https://api.crossref.org/works/10.1234/expiry"))
            .andRespond(withSuccess(response, MediaType.APPLICATION_JSON))

        assertEquals("Expiring study", lookup.byDoi("10.1234/expiry")?.title)
        Thread.sleep(350)
        assertEquals("Expiring study", lookup.byDoi("10.1234/expiry")?.title)

        server.verify()
    }

    @Test
    fun `invalid cache values are discarded and do not block Crossref resolution`() {
        val fixture = fixture()
        val (lookup, server) = lookup(fixture)
        val key = "crossref:doi:v1:10.1234/corrupt"
        redis.opsForValue().set(key, "null")
        server.expect(requestTo("https://api.crossref.org/works/10.1234/corrupt"))
            .andRespond(withSuccess(
                """{"message":{"DOI":"10.1234/corrupt","title":["Recovered study"],"author":[],"issued":{"date-parts":[[2024]]}}}""",
                MediaType.APPLICATION_JSON,
            ))

        assertEquals("Recovered study", lookup.byDoi("10.1234/corrupt")?.title)
        assertTrue(redis.hasKey(key))
        server.verify()
    }

    @Test
    fun `cache hit still requires provider selection and per-run consent`() {
        val fixture = fixture()
        val (consentedLookup, server) = lookup(fixture)
        server.expect(requestTo("https://api.crossref.org/works/10.1234/consent"))
            .andRespond(withSuccess(
                """{"message":{"DOI":"10.1234/consent","title":["Consented study"],"author":[],"issued":{"date-parts":[[2024]]}}}""",
                MediaType.APPLICATION_JSON,
            ))
        assertEquals("Consented study", consentedLookup.byDoi("10.1234/consent")?.title)

        val unconsentedConfiguration = fixture.configuration.copy(externalProviderConsents = emptyList())
        val unselectedConfiguration = fixture.configuration.copy(
            referenceResolution = fixture.configuration.referenceResolution.copy(provider = null),
        )
        listOf(unconsentedConfiguration, unselectedConfiguration).forEach { configuration ->
            val builder = RestClient.builder().baseUrl("https://api.crossref.org")
            val gatedServer = MockRestServiceServer.bindTo(builder).build()
            val gatedLookup = CrossrefScholarlyMetadataLookup(
                client = builder.build(),
                objectMapper = objectMapper,
                callGate = ProviderCallGate(fixture.catalog),
                configuration = configuration,
                contactEmail = null,
                cache = cache(),
            )

            assertThrows(ProviderCallRejectedException::class.java) { gatedLookup.byDoi("10.1234/consent") }
            gatedServer.verify()
        }
        server.verify()
    }

    @Test
    fun `invalidating one DOI leaves unrelated DOI and search entries available`() {
        val fixture = fixture()
        val (lookup, server) = lookup(fixture)
        val response = { doi: String ->
            """{"message":{"DOI":"$doi","title":["Study $doi"],"author":[],"issued":{"date-parts":[[2024]]}}}"""
        }
        server.expect(requestTo("https://api.crossref.org/works/10.1234/first"))
            .andRespond(withSuccess(response("10.1234/first"), MediaType.APPLICATION_JSON))
        server.expect(requestTo("https://api.crossref.org/works/10.1234/second"))
            .andRespond(withSuccess(response("10.1234/second"), MediaType.APPLICATION_JSON))
        server.expect(requestTo("https://api.crossref.org/works?query.bibliographic=A%20search-only%20work&rows=10"))
            .andRespond(withSuccess("""{"message":{"items":[]}}""", MediaType.APPLICATION_JSON))
        server.expect(requestTo("https://api.crossref.org/works/10.1234/first"))
            .andRespond(withSuccess(response("10.1234/first"), MediaType.APPLICATION_JSON))

        lookup.byDoi("10.1234/first")
        lookup.byDoi("10.1234/second")
        lookup.search(BibliographyReference("A search-only work", emptyList(), null, null, "JOURNAL_ARTICLE"))

        assertTrue(cache().invalidateDoi("doi:10.1234/first"))
        assertFalse(redis.hasKey("crossref:doi:v1:10.1234/first"))
        assertTrue(redis.hasKey("crossref:doi:v1:10.1234/second"))
        val searchKey = redis.keys("crossref:search:v1:*").single()
        assertTrue(lookup.search(BibliographyReference("A search-only work", emptyList(), null, null, "JOURNAL_ARTICLE")).isEmpty())
        assertEquals("Study 10.1234/first", lookup.byDoi("10.1234/first")?.title)
        assertEquals("Study 10.1234/second", lookup.byDoi("10.1234/second")?.title)
        assertTrue(cache().invalidateSearch("  A SEARCH-only   work "))
        assertFalse(redis.hasKey(searchKey))
        assertTrue(redis.hasKey("crossref:doi:v1:10.1234/second"))

        server.verify()
    }

    private fun lookup(
        fixture: ProviderFixture,
        cache: CrossrefLookupCache = cache(),
    ): Pair<CrossrefScholarlyMetadataLookup, MockRestServiceServer> {
        val builder = RestClient.builder().baseUrl("https://api.crossref.org")
        val server = MockRestServiceServer.bindTo(builder).build()
        return CrossrefScholarlyMetadataLookup(
            client = builder.build(),
            objectMapper = objectMapper,
            callGate = ProviderCallGate(fixture.catalog),
            configuration = fixture.configuration,
            contactEmail = fixture.contactEmail,
            cache = cache,
        ) to server
    }

    private fun fixture(contactEmail: String? = null): ProviderFixture {
        val catalog = ProviderCatalog.safeDefaults(
            crossrefEnabled = true,
            crossrefRetentionDisclosure = "Reviewed test retention disclosure.",
            crossrefContactEmail = contactEmail,
        )
        val consentCategories = listOfNotNull(
            DataCategory.BIBLIOGRAPHIC_METADATA.id,
            DataCategory.PROVIDER_CONTACT_EMAIL.id.takeIf { !contactEmail.isNullOrBlank() },
        )
        val configuration = RunConfigurationFactory(
            objectMapper = objectMapper,
            providerCatalog = catalog,
            parserId = "grobid",
            parserVersion = "0.9.1-crf",
            languageDetectorVersion = "0.6",
            limits = ValidationLimitsSnapshot(1_000_000, 20, 100_000, 100_000, 100, 0.65),
        ).from(
            RunConfigurationRequest(
                scholarlyMetadataProvider = "crossref",
                externalProviderConsents = listOf(externalProviderConsent(catalog, "crossref", consentCategories)),
            ),
        )
        return ProviderFixture(catalog, configuration, contactEmail)
    }

    private fun cache(
        positiveTtl: Duration = Duration.ofDays(30),
        negativeTtl: Duration = Duration.ofHours(1),
    ) = RedisCrossrefLookupCache(RedisProviderCacheStore(redis), objectMapper, positiveTtl, negativeTtl)

    private data class ProviderFixture(
        val catalog: ProviderCatalog,
        val configuration: AnalysisConfigurationSnapshot,
        val contactEmail: String?,
    )

    companion object {
        private val objectMapper = jacksonObjectMapper()
        private val redisContainer = GenericContainer<Nothing>(DockerImageName.parse("redis:7.4.2-alpine")).apply {
            withExposedPorts(6379)
        }

        @Container
        @JvmStatic
        val redisService: GenericContainer<Nothing> = redisContainer

        private lateinit var connectionFactory: LettuceConnectionFactory
        private lateinit var redis: StringRedisTemplate

        @BeforeAll
        @JvmStatic
        fun setUpRedis() {
            connectionFactory = LettuceConnectionFactory(
                RedisStandaloneConfiguration(redisService.host, redisService.getMappedPort(6379)),
            )
            connectionFactory.afterPropertiesSet()
            redis = StringRedisTemplate(connectionFactory).apply { afterPropertiesSet() }
        }

        @AfterAll
        @JvmStatic
        fun closeRedis() {
            if (::connectionFactory.isInitialized) connectionFactory.destroy()
        }
    }

    @BeforeEach
    fun flushRedis() {
        redis.connectionFactory!!.connection.serverCommands().flushDb()
    }
}
