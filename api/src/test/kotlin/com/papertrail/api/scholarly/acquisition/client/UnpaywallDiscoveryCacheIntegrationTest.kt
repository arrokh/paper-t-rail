package com.papertrail.api.scholarly.acquisition.client

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.papertrail.api.analysis.configuration.AnalysisConfigurationSnapshot
import com.papertrail.api.analysis.configuration.RunConfigurationFactory
import com.papertrail.api.analysis.configuration.ValidationLimitsSnapshot
import com.papertrail.api.analysis.http.RunConfigurationRequest
import com.papertrail.api.infrastructure.cache.RedisProviderCacheStore
import com.papertrail.api.infrastructure.providers.DataCategory
import com.papertrail.api.infrastructure.providers.ProviderCallGate
import com.papertrail.api.infrastructure.providers.ProviderCallRejectedException
import com.papertrail.api.infrastructure.providers.ProviderCatalog
import com.papertrail.api.infrastructure.providers.externalProviderConsent
import com.papertrail.api.scholarly.acquisition.domain.OpenAccessDiscovery
import com.papertrail.api.scholarly.acquisition.domain.OpenAccessLocation
import com.papertrail.api.scholarly.references.client.BibliographyReference
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
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
import org.hamcrest.Matchers.containsString
import org.testcontainers.containers.GenericContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit
import com.papertrail.api.external.unpaywall.RedisUnpaywallDiscoveryCache
import com.papertrail.api.external.unpaywall.UnpaywallDiscoveryCache
import com.papertrail.api.external.unpaywall.UnpaywallDiscoveryCacheEntry
import com.papertrail.api.external.unpaywall.UnpaywallOpenAccessProviderFactory

@Testcontainers
class UnpaywallDiscoveryCacheIntegrationTest {
    private val objectMapper: ObjectMapper = jacksonObjectMapper().registerKotlinModule().registerModule(JavaTimeModule())
    private val contactEmail = "researcher@example.invalid"
    private val providerCatalog = ProviderCatalog.safeDefaults(
        unpaywallEnabled = true,
        unpaywallRetentionDisclosure = null,
        unpaywallContactEmail = contactEmail,
    )
    private val configurationFactory = RunConfigurationFactory(
        providerCatalog = providerCatalog,
        parserId = "grobid",
        parserVersion = "0.9.1-crf",
        languageDetectorVersion = "0.6",
        limits = ValidationLimitsSnapshot(1_000_000, 20, 100_000, 100_000, 100, 0.65),
    )

    @Test
    fun `normalized DOI reuses discovery and stores only timestamp schema and normalized fields`() {
        val cache = cache()
        val fixture = provider(cache)
        fixture.discoveryServer.expect(requestTo(containsString("https://api.unpaywall.org/v2/")))
            .andExpect(method(HttpMethod.GET))
            .andExpect(queryParam("email", contactEmail))
            .andRespond(withSuccess(
                """{"doi":"10.1234/cache-hit","title":"PRIVATE RAW TITLE","abstract":"Summary","oa_locations":[{"url_for_pdf":" https://8.8.8.8/paper.pdf ","license":" cc-by ","version":" publishedVersion ","host_type":" repository "}],"provider_response_marker":"PRIVATE RAW RESPONSE"}""",
                MediaType.APPLICATION_JSON,
            ))

        val first = fixture.provider.discover(reference("https://doi.org/10.1234/Cache-Hit"))
        val serializedCacheEntry = redis.opsForValue().get("unpaywall:doi:v1:10.1234/cache-hit")
        assertNotNull(cache.findByDoi("10.1234/cache-hit"), "Cache entry was not readable: $serializedCacheEntry")
        val second = fixture.provider.discover(reference("doi:10.1234/cache-hit"))

        assertEquals(first, second)
        assertEquals("https://8.8.8.8/paper.pdf", second!!.locations.single().url)
        assertEquals("cc-by", second.locations.single().license)
        assertEquals("publishedVersion", second.locations.single().version)
        assertEquals("repository", second.locations.single().hostType)
        fixture.discoveryServer.verify()

        val key = "unpaywall:doi:v1:10.1234/cache-hit"
        assertEquals(setOf(key), redis.keys("unpaywall:doi:*").toSet())
        val encoded = requireNotNull(redis.opsForValue().get(key))
        val cached = objectMapper.readTree(encoded)
        assertEquals(
            setOf("schemaVersion", "fetchedAt", "metadataAvailable", "abstractAvailable", "locations"),
            cached.fieldNames().asSequence().toSet(),
        )
        assertEquals(UnpaywallDiscoveryCacheEntry.CURRENT_SCHEMA_VERSION, cached.path("schemaVersion").asInt())
        assertTrue(cached.path("fetchedAt").isTextual || cached.path("fetchedAt").isNumber)
        assertEquals(
            setOf("url", "license", "version", "hostType"),
            cached.path("locations").single().fieldNames().asSequence().toSet(),
        )
        assertFalse(encoded.contains("PRIVATE RAW TITLE"))
        assertFalse(encoded.contains("PRIVATE RAW RESPONSE"))
        assertFalse(encoded.contains("Summary"))
        assertFalse(encoded.contains(contactEmail))
    }

    @Test
    fun `reads discovery cache JSON written before provider files were relocated`() {
        redis.opsForValue().set(
            "unpaywall:doi:v1:10.1234/legacy",
            """{"schemaVersion":1,"fetchedAt":"2026-10-05T00:00:00Z","metadataAvailable":true,"abstractAvailable":false,"locations":[{"url":"https://example.invalid/legacy.pdf","license":"cc-by","version":"publishedVersion","hostType":"repository"}]}""",
        )

        val cached = requireNotNull(cache().findByDoi("10.1234/legacy"))

        assertEquals(1, cached.schemaVersion)
        assertEquals(Instant.parse("2026-10-05T00:00:00Z"), cached.fetchedAt)
        assertTrue(cached.metadataAvailable)
        assertFalse(cached.abstractAvailable)
        assertEquals("https://example.invalid/legacy.pdf", cached.locations.single().url)
        assertEquals("cc-by", cached.locations.single().license)
    }

    @Test
    fun `not-found result is negative-cached for the configured TTL`() {
        val fixture = provider(cache(positiveTtl = Duration.ofDays(1), negativeTtl = Duration.ofSeconds(2)))
        fixture.discoveryServer.expect(requestTo(containsString("https://api.unpaywall.org/v2/")))
            .andRespond(withStatus(HttpStatus.NOT_FOUND))

        assertEquals(null, fixture.provider.discover(reference("10.1234/missing")))
        assertEquals(null, fixture.provider.discover(reference("doi:10.1234/missing")))

        val key = "unpaywall:doi:v1:10.1234/missing"
        assertTrue(redis.hasKey(key))
        assertTrue(requireNotNull(redis.getExpire(key, TimeUnit.SECONDS)) in 0L..2L)
        val cached = objectMapper.readTree(requireNotNull(redis.opsForValue().get(key)))
        assertFalse(cached.path("metadataAvailable").asBoolean())
        assertFalse(cached.path("abstractAvailable").asBoolean())
        assertTrue(cached.path("locations").isEmpty)
        fixture.discoveryServer.verify()
    }

    @Test
    fun `invalid cached values are discarded and refetched`() {
        val cache = cache()
        val key = "unpaywall:doi:v1:10.1234/corrupt"
        redis.opsForValue().set(key, "null")
        val fixture = provider(cache)
        fixture.discoveryServer.expect(requestTo(containsString("https://api.unpaywall.org/v2/")))
            .andExpect(queryParam("email", contactEmail))
            .andRespond(withSuccess(
                """{"doi":"10.1234/corrupt","abstract":"available","oa_locations":[]}""",
                MediaType.APPLICATION_JSON,
            ))

        assertTrue(fixture.provider.discover(reference("10.1234/corrupt"))!!.abstractAvailable)

        assertTrue(redis.hasKey(key))
        fixture.discoveryServer.verify()
    }

    @Test
    fun `expired positive result is fetched again`() {
        val fixture = provider(cache(positiveTtl = Duration.ofMillis(250), negativeTtl = Duration.ofSeconds(2)))
        val response = """{"doi":"10.1234/expiry","abstract":"available","oa_locations":[]}"""
        fixture.discoveryServer.expect(ExpectedCount.twice(), requestTo(containsString("https://api.unpaywall.org/v2/")))
            .andRespond(withSuccess(response, MediaType.APPLICATION_JSON))

        assertTrue(fixture.provider.discover(reference("10.1234/expiry"))!!.metadataAvailable)
        Thread.sleep(350)
        assertTrue(fixture.provider.discover(reference("10.1234/expiry"))!!.metadataAvailable)

        fixture.discoveryServer.verify()
    }

    @Test
    fun `cached discovery cannot bypass current provider selection and consent`() {
        val cache = cache()
        val priming = provider(cache)
        priming.discoveryServer.expect(requestTo(containsString("https://api.unpaywall.org/v2/")))
            .andExpect(queryParam("email", contactEmail))
            .andRespond(withSuccess(
                """{"doi":"10.1234/consent","abstract":"available","oa_locations":[]}""",
                MediaType.APPLICATION_JSON,
            ))
        priming.provider.discover(reference("10.1234/consent"))
        priming.discoveryServer.verify()

        val unconsented = provider(cache, configuredRun().copy(externalProviderConsents = emptyList()))
        unconsented.discoveryServer.expect(ExpectedCount.never(), requestTo(containsString("https://api.unpaywall.org")))
        assertThrows(ProviderCallRejectedException::class.java) {
            unconsented.provider.discover(reference("10.1234/consent"))
        }
        unconsented.discoveryServer.verify()

        val unselectedConfiguration = configuredRun().copy(
            openAccess = configuredRun().openAccess.copy(provider = "recorded-fixtures"),
        )
        val unselected = provider(cache, unselectedConfiguration)
        unselected.discoveryServer.expect(ExpectedCount.never(), requestTo(containsString("https://api.unpaywall.org")))
        assertThrows(ProviderCallRejectedException::class.java) {
            unselected.provider.discover(reference("10.1234/consent"))
        }
        unselected.discoveryServer.verify()
    }

    @Test
    fun `cached locations are checked against the current legal policy before content requests`() {
        val cache = cache()
        val location = OpenAccessLocation(
            "https://8.8.8.8/paper.pdf",
            "all-rights-reserved",
            "publishedVersion",
            "repository",
            UnpaywallOpenAccessProviderFactory.UNPAYWALL_PROVIDER,
        )
        cache.storeByDoi(
            "10.1234/restricted",
            OpenAccessDiscovery(true, false, listOf(location), UnpaywallOpenAccessProviderFactory.UNPAYWALL_PROVIDER, Instant.now()),
            Instant.now(),
        )
        val fixture = provider(cache)
        fixture.discoveryServer.expect(ExpectedCount.never(), requestTo(containsString("https://api.unpaywall.org")))
        fixture.contentServer.expect(ExpectedCount.never(), requestTo("https://8.8.8.8/paper.pdf"))

        val cachedLocation = fixture.provider.discover(reference("10.1234/restricted"))!!.locations.single()
        assertThrows(IllegalArgumentException::class.java) { fixture.provider.fetch(cachedLocation) }

        fixture.discoveryServer.verify()
        fixture.contentServer.verify()
    }

    @Test
    fun `invalidating one normalized DOI leaves other provider entries intact`() {
        val cache = cache()
        val now = Instant.now()
        cache.storeByDoi("10.1234/first", null, now)
        cache.storeByDoi("10.1234/second", null, now)

        assertTrue(cache.invalidateDoi("https://doi.org/10.1234/FIRST"))

        assertFalse(redis.hasKey("unpaywall:doi:v1:10.1234/first"))
        assertTrue(redis.hasKey("unpaywall:doi:v1:10.1234/second"))
    }

    private fun reference(doi: String) = BibliographyReference("Cached study", emptyList(), 2024, doi, "JOURNAL_ARTICLE")

    private fun configuredRun() = configurationFactory.from(
        RunConfigurationRequest(
            openAccessProvider = UnpaywallOpenAccessProviderFactory.UNPAYWALL_PROVIDER,
            externalProviderConsents = listOf(externalProviderConsent(
                providerCatalog,
                UnpaywallOpenAccessProviderFactory.UNPAYWALL_PROVIDER,
                listOf(
                    DataCategory.BIBLIOGRAPHIC_METADATA.id,
                    DataCategory.CITED_PAPER_LOCATION.id,
                    DataCategory.PROVIDER_CONTACT_EMAIL.id,
                ),
            )),
        ),
    )

    private fun provider(
        cache: UnpaywallDiscoveryCache,
        configuration: AnalysisConfigurationSnapshot = configuredRun(),
    ): ProviderFixture {
        val discoveryBuilder = RestClient.builder().baseUrl("https://api.unpaywall.org")
        val contentBuilder = RestClient.builder()
        val discoveryServer = MockRestServiceServer.bindTo(discoveryBuilder).build()
        val contentServer = MockRestServiceServer.bindTo(contentBuilder).build()
        val provider = UnpaywallOpenAccessProviderFactory(
            providerCallGate = ProviderCallGate(providerCatalog),
            discoveryCache = cache,
            unpaywallClient = discoveryBuilder.build(),
            contentClient = contentBuilder.build(),
            contactEmail = contactEmail,
            maximumBytes = 1_000_000,
        ).forRun(configuration)
        return ProviderFixture(provider, discoveryServer, contentServer)
    }

    private fun cache(
        positiveTtl: Duration = Duration.ofHours(24),
        negativeTtl: Duration = Duration.ofHours(1),
    ) = RedisUnpaywallDiscoveryCache(RedisProviderCacheStore(redis), positiveTtl, negativeTtl)

    private data class ProviderFixture(
        val provider: OpenAccessProvider,
        val discoveryServer: MockRestServiceServer,
        val contentServer: MockRestServiceServer,
    )

    companion object {
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
