package com.papertrail.api.scholarly.references.client

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.papertrail.api.analysis.configuration.ProviderChainSnapshot
import com.papertrail.api.analysis.execution.domain.CaptureFidelity
import com.papertrail.api.analysis.execution.domain.ExecutionOperationId
import com.papertrail.api.analysis.execution.domain.ExecutionSpanArtifactSpec
import com.papertrail.api.analysis.execution.domain.ExecutionSpanHandle
import com.papertrail.api.analysis.execution.domain.ExecutionSpanSpec
import com.papertrail.api.analysis.execution.domain.SanitizedExecutionArtifact
import com.papertrail.api.analysis.execution.repository.AnalysisRunExecutionRepository
import com.papertrail.api.analysis.execution.service.AnalysisRunExecutionService
import com.papertrail.api.analysis.execution.service.ExecutionCaptureSanitizer
import com.papertrail.api.analysis.configuration.RunConfigurationFactory
import com.papertrail.api.analysis.configuration.ValidationLimitsSnapshot
import com.papertrail.api.scholarly.references.attempts.domain.ReferenceResolutionAttemptOutcomeCode
import com.papertrail.api.analysis.http.ExternalProviderConsentRequest
import com.papertrail.api.analysis.http.RunConfigurationRequest
import com.papertrail.api.external.crossref.CrossrefChainProviderException
import com.papertrail.api.external.crossref.CrossrefLookupCache
import com.papertrail.api.external.crossref.CrossrefScholarlyMetadataLookup
import com.papertrail.api.infrastructure.providers.DataCategory
import com.papertrail.api.infrastructure.providers.ProviderCallGate
import com.papertrail.api.infrastructure.providers.ProviderCallRejectedException
import com.papertrail.api.infrastructure.providers.ProviderCatalog
import com.papertrail.api.infrastructure.providers.configuredExternalProviderCatalog
import com.papertrail.api.infrastructure.providers.externalProviderConsent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.mock.http.client.MockClientHttpResponse
import org.springframework.test.web.client.ExpectedCount
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.header
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import java.io.IOException
import java.io.InputStream
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import org.hamcrest.Matchers.containsString

class CrossrefScholarlyMetadataLookupTest {
    private val objectMapper = jacksonObjectMapper()
    private val providerCatalog = configuredExternalProviderCatalog()
    private val gate = ProviderCallGate(providerCatalog)
    private val runConfigurationFactory = RunConfigurationFactory(
        providerCatalog = providerCatalog,
        parserId = "grobid",
        parserVersion = "0.9.1-crf",
        languageDetectorVersion = "0.6",
        limits = ValidationLimitsSnapshot(1_000_000, 20, 100_000, 100_000, 100, 0.65),
    )

    @Test
    fun `missing per-run bibliography metadata consent results in no Crossref HTTP request`() {
        val builder = RestClient.builder().baseUrl("https://api.crossref.org")
        val server = MockRestServiceServer.bindTo(builder).build()
        server.expect(ExpectedCount.never(), requestTo(containsString("api.crossref.org")))
        val configuration = crossrefConfiguration().copy(externalProviderConsents = emptyList())
        val lookup = CrossrefScholarlyMetadataLookup(builder.build(), gate, configuration, null, NoOpCrossrefLookupCache)

        assertThrows(ProviderCallRejectedException::class.java) {
            lookup.byDoi("10.1234/unconsented")
        }

        server.verify()
    }

    @Test
    fun `consented DOI lookup sends the request and returns Crossref metadata`() {
        val builder = RestClient.builder().baseUrl("https://api.crossref.org")
        val server = MockRestServiceServer.bindTo(builder).build()
        server.expect(requestTo("https://api.crossref.org/works/10.1234/confirmed"))
            .andExpect(method(HttpMethod.GET))
            .andRespond(withSuccess(
                """{"message":{"DOI":"10.1234/confirmed","title":["Confirmed study"],"author":[{"given":"Ada","family":"Researcher"}],"published-print":{"date-parts":[[2024]]}}}""",
                MediaType.APPLICATION_JSON,
            ))
        val lookup = CrossrefScholarlyMetadataLookup(builder.build(), gate, crossrefConfiguration(), null, NoOpCrossrefLookupCache)

        val work = lookup.byDoi("10.1234/confirmed")

        assertEquals("10.1234/confirmed", work?.doi)
        assertEquals("Confirmed study", work?.title)
        assertEquals(listOf("Ada Researcher"), work?.authors)
        assertEquals(2024, work?.year)
        server.verify()
    }

    @Test
    fun `oversized Crossref responses remain parseable for metadata lookup`() {
        val response = objectMapper.createObjectNode()
        val message = response.putObject("message")
        message.put("DOI", "10.1234/large-response")
        message.putArray("title").add("Large response study")
        message.put("abstract", "x".repeat(MAX_CHAIN_JSON_BYTES + 1))
        val builder = RestClient.builder().baseUrl("https://api.crossref.org")
        val server = MockRestServiceServer.bindTo(builder).build()
        server.expect(requestTo("https://api.crossref.org/works/10.1234/large-response"))
            .andExpect(method(HttpMethod.GET))
            .andRespond(withSuccess(objectMapper.writeValueAsString(response), MediaType.APPLICATION_JSON))
        val lookup = CrossrefScholarlyMetadataLookup(builder.build(), gate, crossrefConfiguration(), null, NoOpCrossrefLookupCache)

        val work = lookup.byDoi("10.1234/large-response")

        assertEquals("10.1234/large-response", work?.doi)
        assertEquals("Large response study", work?.title)
        server.verify()
    }

    @Test
    fun `metadata search sends only the bibliography query and parses returned works`() {
        val builder = RestClient.builder().baseUrl("https://api.crossref.org")
        val server = MockRestServiceServer.bindTo(builder).build()
        server.expect(requestTo("https://api.crossref.org/works?query.bibliographic=A%20test%20title%20Ada%20Researcher%202024&rows=10"))
            .andExpect(method(HttpMethod.GET))
            .andExpect(queryParam("query.bibliographic", "A%20test%20title%20Ada%20Researcher%202024"))
            .andRespond(withSuccess(
                """{"message":{"items":[{"DOI":"10.1234/search-match","title":["A test title"],"author":[{"given":"Ada","family":"Researcher"}],"issued":{"date-parts":[[2024]]}}]}}""",
                MediaType.APPLICATION_JSON,
            ))
        val lookup = CrossrefScholarlyMetadataLookup(builder.build(), gate, crossrefConfiguration(), null, NoOpCrossrefLookupCache)

        val matches = lookup.search(BibliographyReference("A test title", listOf("Ada Researcher"), 2024, null, "JOURNAL_ARTICLE"))

        assertEquals(1, matches.size)
        assertEquals("10.1234/search-match", matches.single().doi)
        assertEquals("A test title", matches.single().title)
        server.verify()
    }

    @Test
    fun `pinned chain exposes bounded retry classification for transient HTTP responses`() {
        val catalog = providerChainCatalog()
        val builder = RestClient.builder().baseUrl("https://api.crossref.org")
        val server = MockRestServiceServer.bindTo(builder).build()
        server.expect(requestTo("https://api.crossref.org/works/10.1234/transient"))
            .andExpect(method(HttpMethod.GET))
            .andRespond(
                withStatus(HttpStatus.SERVICE_UNAVAILABLE)
                    .header(HttpHeaders.RETRY_AFTER, "2")
                    .body("provider-private-response-body"),
            )
        val lookup = CrossrefScholarlyMetadataLookup(
            builder.build(),
            ProviderCallGate(catalog),
            chainConfiguration(catalog),
            null,
            NoOpCrossrefLookupCache,
            clock = Clock.fixed(Instant.parse("2015-10-21T07:27:00Z"), ZoneOffset.UTC),
        )

        val exception = assertThrows(CrossrefChainProviderException::class.java) {
            lookup.byDoi("10.1234/transient")
        }

        assertEquals(ReferenceResolutionAttemptOutcomeCode.RETRYABLE_FAILURE, exception.outcomeCode)
        assertEquals(503, exception.httpStatus)
        assertEquals(2_000, exception.retryAfterMillis)
        assertEquals("Crossref scholarly metadata lookup failed.", exception.message)
        assertNull(exception.cause)
        assertFalse(exception.message.orEmpty().contains("provider-private-response-body"))
        server.verify()
    }

    @Test
    fun `pinned chain classifies unsupported server responses as permanent`() {
        val catalog = providerChainCatalog()
        val builder = RestClient.builder().baseUrl("https://api.crossref.org")
        val server = MockRestServiceServer.bindTo(builder).build()
        server.expect(requestTo("https://api.crossref.org/works/10.1234/unsupported"))
            .andExpect(method(HttpMethod.GET))
            .andRespond(withStatus(HttpStatus.NOT_IMPLEMENTED).header(HttpHeaders.RETRY_AFTER, "2").body("private response"))
        val lookup = CrossrefScholarlyMetadataLookup(
            builder.build(), ProviderCallGate(catalog), chainConfiguration(catalog), null, NoOpCrossrefLookupCache,
        )

        val exception = assertThrows(CrossrefChainProviderException::class.java) {
            lookup.byDoi("10.1234/unsupported")
        }

        assertEquals(ReferenceResolutionAttemptOutcomeCode.PERMANENT_FAILURE, exception.outcomeCode)
        assertEquals(501, exception.httpStatus)
        assertNull(exception.retryAfterMillis)
        server.verify()
    }

    @Test
    fun `pinned chain rejects malformed success envelopes but accepts valid empty search`() {
        val catalog = providerChainCatalog()
        val builder = RestClient.builder().baseUrl("https://api.crossref.org")
        val server = MockRestServiceServer.bindTo(builder).build()
        server.expect(requestTo(containsString("/works/10.1234/missing-message")))
            .andRespond(withSuccess("{\"notMessage\":{}}", MediaType.APPLICATION_JSON))
        server.expect(requestTo(containsString("/works/10.1234/malformed-message")))
            .andRespond(withSuccess("{\"message\":[]}", MediaType.APPLICATION_JSON))
        server.expect(requestTo("https://api.crossref.org/works?query.bibliographic=Missing%20items&rows=10"))
            .andRespond(withSuccess("{\"message\":{}}", MediaType.APPLICATION_JSON))
        server.expect(requestTo("https://api.crossref.org/works?query.bibliographic=Malformed%20items&rows=10"))
            .andRespond(withSuccess("{\"message\":{\"items\":{}}}", MediaType.APPLICATION_JSON))
        server.expect(requestTo("https://api.crossref.org/works?query.bibliographic=Empty%20result&rows=10"))
            .andRespond(withSuccess("{\"message\":{\"items\":[]}}", MediaType.APPLICATION_JSON))
        val lookup = CrossrefScholarlyMetadataLookup(
            builder.build(), ProviderCallGate(catalog), chainConfiguration(catalog), null, NoOpCrossrefLookupCache,
        )

        val missingMessage = assertThrows(CrossrefChainProviderException::class.java) {
            lookup.byDoi("10.1234/missing-message")
        }
        val malformedMessage = assertThrows(CrossrefChainProviderException::class.java) {
            lookup.byDoi("10.1234/malformed-message")
        }
        val missingItems = assertThrows(CrossrefChainProviderException::class.java) {
            lookup.search(BibliographyReference("Missing items", emptyList(), null, null, "JOURNAL_ARTICLE"))
        }
        val malformedItems = assertThrows(CrossrefChainProviderException::class.java) {
            lookup.search(BibliographyReference("Malformed items", emptyList(), null, null, "JOURNAL_ARTICLE"))
        }

        assertEquals(ReferenceResolutionAttemptOutcomeCode.UNUSABLE, missingMessage.outcomeCode)
        assertEquals(ReferenceResolutionAttemptOutcomeCode.UNUSABLE, malformedMessage.outcomeCode)
        assertEquals(ReferenceResolutionAttemptOutcomeCode.UNUSABLE, missingItems.outcomeCode)
        assertEquals(ReferenceResolutionAttemptOutcomeCode.UNUSABLE, malformedItems.outcomeCode)
        assertEquals(emptyList<ScholarlyWork>(), lookup.search(BibliographyReference("Empty result", emptyList(), null, null, "JOURNAL_ARTICLE")))
        server.verify()
    }

    @Test
    fun `pinned chain rejects oversized DOI response before parsing with a safe provider exception`() {
        val oversizedResponse = crossrefResponseOfSize(MAX_CHAIN_JSON_BYTES, "10.1234/oversized-doi", "Oversized DOI study") + " "
        val builder = RestClient.builder().baseUrl("https://api.crossref.org")
        val server = MockRestServiceServer.bindTo(builder).build()
        server.expect(requestTo("https://api.crossref.org/works/10.1234/oversized-doi"))
            .andExpect(method(HttpMethod.GET))
            .andExpect(header(HttpHeaders.ACCEPT_ENCODING, "identity"))
            .andRespond(withSuccess(oversizedResponse, MediaType.APPLICATION_JSON))
        val catalog = providerChainCatalog()
        val lookup = CrossrefScholarlyMetadataLookup(
            builder.build(), ProviderCallGate(catalog), chainConfiguration(catalog), null, NoOpCrossrefLookupCache,
        )

        val exception = assertThrows(CrossrefChainProviderException::class.java) {
            lookup.byDoi("10.1234/oversized-doi")
        }

        assertEquals("Crossref scholarly metadata lookup failed.", exception.message)
        assertEquals(200, exception.httpStatus)
        assertEquals((MAX_CHAIN_JSON_BYTES + 1).toLong(), exception.responseBytes)
        assertEquals(null, exception.cause)
        server.verify()
    }

    @Test
    fun `pinned chain rejects oversized title-search response before parsing with a safe provider exception`() {
        val oversizedResponse = crossrefResponseOfSize(MAX_CHAIN_JSON_BYTES, "10.1234/oversized-title", "Oversized title study") + " "
        val builder = RestClient.builder().baseUrl("https://api.crossref.org")
        val server = MockRestServiceServer.bindTo(builder).build()
        server.expect(requestTo("https://api.crossref.org/works?query.bibliographic=Oversized%20title&rows=10"))
            .andExpect(method(HttpMethod.GET))
            .andExpect(header(HttpHeaders.ACCEPT_ENCODING, "identity"))
            .andRespond(withSuccess(oversizedResponse, MediaType.APPLICATION_JSON))
        val catalog = providerChainCatalog()
        val lookup = CrossrefScholarlyMetadataLookup(
            builder.build(), ProviderCallGate(catalog), chainConfiguration(catalog), null, NoOpCrossrefLookupCache,
        )

        val exception = assertThrows(CrossrefChainProviderException::class.java) {
            lookup.search(BibliographyReference("Oversized title", emptyList(), null, null, "JOURNAL_ARTICLE"))
        }

        assertEquals("Crossref scholarly metadata lookup failed.", exception.message)
        assertEquals(200, exception.httpStatus)
        assertEquals((MAX_CHAIN_JSON_BYTES + 1).toLong(), exception.responseBytes)
        assertEquals(null, exception.cause)
        server.verify()
    }

    @Test
    fun `pinned chain accepts a valid response exactly at the decoded JSON byte limit`() {
        val boundaryResponse = crossrefResponseOfSize(MAX_CHAIN_JSON_BYTES, "10.1234/boundary-doi", "Boundary study")
        assertEquals(MAX_CHAIN_JSON_BYTES, boundaryResponse.toByteArray(Charsets.UTF_8).size)
        val builder = RestClient.builder().baseUrl("https://api.crossref.org")
        val server = MockRestServiceServer.bindTo(builder).build()
        server.expect(requestTo("https://api.crossref.org/works/10.1234/boundary-doi"))
            .andExpect(method(HttpMethod.GET))
            .andExpect(header(HttpHeaders.ACCEPT_ENCODING, "identity"))
            .andRespond(withSuccess(boundaryResponse, MediaType.APPLICATION_JSON))
        val catalog = providerChainCatalog()
        val lookup = CrossrefScholarlyMetadataLookup(
            builder.build(), ProviderCallGate(catalog), chainConfiguration(catalog), null, NoOpCrossrefLookupCache,
        )

        val work = lookup.byDoi("10.1234/boundary-doi")

        assertEquals("10.1234/boundary-doi", work?.doi)
        assertEquals("Boundary study", work?.title)
        server.verify()
    }

    @Test
    fun `pinned chain classifies mid-response IOException as retryable without guessed byte count`() {
        val catalog = providerChainCatalog()
        val builder = RestClient.builder().baseUrl("https://api.crossref.org")
        val server = MockRestServiceServer.bindTo(builder).build()
        server.expect(requestTo("https://api.crossref.org/works/10.1234/disconnected-response"))
            .andExpect(method(HttpMethod.GET))
            .andExpect(header(HttpHeaders.ACCEPT_ENCODING, "identity"))
            .andRespond {
                MockClientHttpResponse(disconnectingStream(), HttpStatus.OK).apply {
                    headers.contentType = MediaType.APPLICATION_JSON
                    headers.contentLength = 1_234
                }
            }
        val lookup = CrossrefScholarlyMetadataLookup(
            builder.build(), ProviderCallGate(catalog), chainConfiguration(catalog), null, NoOpCrossrefLookupCache,
        )

        val failure = assertThrows(CrossrefChainProviderException::class.java) {
            lookup.byDoi("10.1234/disconnected-response")
        }

        assertEquals(ReferenceResolutionAttemptOutcomeCode.RETRYABLE_FAILURE, failure.outcomeCode)
        assertEquals(200, failure.httpStatus)
        assertNull(failure.responseBytes)
        server.verify()
    }

    @Test
    fun `pinned chain rejects unsupported response content encoding`() {
        val builder = RestClient.builder().baseUrl("https://api.crossref.org")
        val server = MockRestServiceServer.bindTo(builder).build()
        server.expect(requestTo("https://api.crossref.org/works/10.1234/unsupported-encoding"))
            .andExpect(method(HttpMethod.GET))
            .andExpect(header(HttpHeaders.ACCEPT_ENCODING, "identity"))
            .andRespond(
                withSuccess(
                    """{"message":{"DOI":"10.1234/unsupported-encoding","title":["Unsupported encoding study"],"author":[]}}""",
                    MediaType.APPLICATION_JSON,
                ).header(HttpHeaders.CONTENT_ENCODING, "gzip")
                    .header(HttpHeaders.CONTENT_LENGTH, "1234"),
            )
        val catalog = providerChainCatalog()
        val lookup = CrossrefScholarlyMetadataLookup(
            builder.build(), ProviderCallGate(catalog), chainConfiguration(catalog), null, NoOpCrossrefLookupCache,
        )

        val exception = assertThrows(CrossrefChainProviderException::class.java) {
            lookup.byDoi("10.1234/unsupported-encoding")
        }

        assertEquals("Crossref scholarly metadata lookup failed.", exception.message)
        assertEquals(200, exception.httpStatus)
        assertNull(exception.responseBytes)
        server.verify()
    }

    @Test
    fun `chain response with trailing JSON tokens is rejected before capture`() {
        val catalog = providerChainCatalog()
        val builder = RestClient.builder().baseUrl("https://api.crossref.org")
        val server = MockRestServiceServer.bindTo(builder).build()
        server.expect(requestTo("https://api.crossref.org/works/10.1234/malformed-response"))
            .andExpect(method(HttpMethod.GET))
            .andExpect(header(HttpHeaders.ACCEPT_ENCODING, "identity"))
            .andRespond(withSuccess(
                """{"message":{"DOI":"10.1234/malformed-response","title":["Trailing token study"],"author":[]}} trailing token""",
                MediaType.APPLICATION_JSON,
            ))
        val trace = chainExecutionTrace(builder.build(), catalog)

        try {
            assertThrows(CrossrefChainProviderException::class.java) {
                trace.record { trace.lookup.byDoi("10.1234/malformed-response") }
            }
        } finally {
            server.verify()
        }

        val artifacts = trace.artifacts()
        assertEquals(listOf("REQUEST"), artifacts.map { it.first.role })
        assertEquals(null, artifacts.single().second.content)
    }

    @Test
    fun `chain execution capture omits DOI and provider response content`() {
        val catalog = providerChainCatalog()
        val builder = RestClient.builder().baseUrl("https://api.crossref.org")
        val server = MockRestServiceServer.bindTo(builder).build()
        server.expect(requestTo("https://api.crossref.org/works/10.1234/capture-private-doi"))
            .andExpect(method(HttpMethod.GET))
            .andRespond(withSuccess(
                """{"message":{"DOI":"10.1234/capture-private-doi","title":["Not for capture"],"author":[]}}""",
                MediaType.APPLICATION_JSON,
            ))
        val trace = chainExecutionTrace(builder.build(), catalog)

        val result = try {
            trace.record { trace.lookup.byDoi("10.1234/capture-private-doi") }
        } finally {
            server.verify()
        }

        assertEquals("Not for capture", result?.title)
        val artifacts = trace.artifacts()
        assertEquals(listOf("REQUEST", "RESPONSE"), artifacts.map { it.first.role })
        assertTrue(artifacts.all { it.second.content == null })
        assertTrue(artifacts.all { it.second.fidelity == CaptureFidelity.OMITTED })
    }

    @Test
    fun `pinned chain authorizes exact Crossref DOI consent and bypasses the legacy cache`() {
        val contactEmail = "chain-contact@example.invalid"
        val catalog = providerChainCatalog(contactEmail)
        val configuration = chainConfiguration(catalog)
        val cachedWork = ScholarlyWork("10.1234/chain-doi", "Legacy cached title", emptyList(), null)
        val cache = RecordingCrossrefLookupCache(doiHit = listOf(cachedWork))
        val builder = RestClient.builder().baseUrl("https://api.crossref.org")
        val server = MockRestServiceServer.bindTo(builder).build()
        server.expect(requestTo("https://api.crossref.org/works/10.1234/chain-doi?mailto=chain-contact@example.invalid"))
            .andExpect(method(HttpMethod.GET))
            .andRespond(withSuccess(
                """{"message":{"DOI":"10.1234/chain-doi","title":["Chain provider title"],"author":[]}}""",
                MediaType.APPLICATION_JSON,
            ))
        val lookup = CrossrefScholarlyMetadataLookup(
            builder.build(), ProviderCallGate(catalog), configuration, contactEmail, cache,
        )

        val result = lookup.byDoi("10.1234/chain-doi")

        assertEquals("Chain provider title", result?.title)
        assertEquals(emptyList<String>(), cache.doiReads)
        assertEquals(emptyList<Pair<String, List<ScholarlyWork>>>(), cache.doiWrites)
        assertEquals(emptyList<String>(), cache.searchReads)
        assertEquals(emptyList<Pair<String, List<ScholarlyWork>>>(), cache.searchWrites)
        server.verify()
    }

    @Test
    fun `provider-chain pre-send callback runs only after consent and before Crossref HTTP`() {
        val catalog = providerChainCatalog()
        val configuration = chainConfiguration(catalog)
        val builder = RestClient.builder().baseUrl("https://api.crossref.org")
        val server = MockRestServiceServer.bindTo(builder).build()
        val responseBody = """{"message":{"DOI":"10.1234/before-wire","title":["Before wire"],"author":[]}}"""
        var beforeWire = false
        server.expect(requestTo("https://api.crossref.org/works/10.1234/before-wire"))
            .andRespond { request ->
                assertTrue(beforeWire, "The persisted attempt must start after provider authorization and before HTTP.")
                withSuccess(responseBody, MediaType.APPLICATION_JSON).createResponse(request)
            }
        val lookup = CrossrefScholarlyMetadataLookup(
            builder.build(), ProviderCallGate(catalog), configuration, null, NoOpCrossrefLookupCache,
        )

        val result = lookup.byDoiForChain("10.1234/before-wire") { beforeWire = true }

        assertEquals("10.1234/before-wire", result.value?.doi)
        assertEquals(200, result.httpStatus)
        assertEquals(responseBody.toByteArray().size.toLong(), result.responseBytes)
        server.verify()
    }

    @Test
    fun `provider-chain consent rejection does not invoke the pre-send callback`() {
        val catalog = providerChainCatalog()
        val configuration = chainConfiguration(catalog).copy(
            externalProviderConsents = chainConfiguration(catalog).externalProviderConsents
                .filterNot { it.providerId == ProviderChainSnapshot.CROSSREF_PROVIDER_ID },
        )
        val builder = RestClient.builder().baseUrl("https://api.crossref.org")
        val server = MockRestServiceServer.bindTo(builder).build()
        server.expect(ExpectedCount.never(), requestTo(containsString("api.crossref.org")))
        var beforeWire = false
        val lookup = CrossrefScholarlyMetadataLookup(
            builder.build(), ProviderCallGate(catalog), configuration, null, NoOpCrossrefLookupCache,
        )

        assertThrows(ProviderCallRejectedException::class.java) {
            lookup.byDoiForChain("10.1234/rejected") { beforeWire = true }
        }

        assertFalse(beforeWire)
        server.verify()
    }

    @Test
    fun `pinned chain uses title-only Crossref search without reading or writing legacy cache`() {
        val catalog = providerChainCatalog()
        val configuration = chainConfiguration(catalog)
        val cachedWork = ScholarlyWork("10.1234/legacy-search-hit", "Legacy cached title", emptyList(), null)
        val cache = RecordingCrossrefLookupCache(searchHit = listOf(cachedWork))
        val builder = RestClient.builder().baseUrl("https://api.crossref.org")
        val server = MockRestServiceServer.bindTo(builder).build()
        server.expect(requestTo("https://api.crossref.org/works?query.bibliographic=A%20test%20title&rows=10"))
            .andExpect(method(HttpMethod.GET))
            .andRespond(withSuccess(
                """{"message":{"items":[{"DOI":"10.1234/chain-search","title":["A test title"],"author":[]}]}}""",
                MediaType.APPLICATION_JSON,
            ))
        val lookup = CrossrefScholarlyMetadataLookup(builder.build(), ProviderCallGate(catalog), configuration, null, cache)

        val matches = lookup.search(BibliographyReference("A test title", listOf("Ada Researcher"), 2024, null, "JOURNAL_ARTICLE"))

        assertEquals(listOf("10.1234/chain-search"), matches.map(ScholarlyWork::doi))
        assertEquals(emptyList<String>(), cache.searchReads)
        assertEquals(emptyList<Pair<String, List<ScholarlyWork>>>(), cache.searchWrites)
        assertEquals(emptyList<String>(), cache.doiReads)
        assertEquals(emptyList<Pair<String, List<ScholarlyWork>>>(), cache.doiWrites)
        server.verify()
    }

    @Test
    fun `pinned chain rejects Crossref before cache or HTTP when its consent is absent`() {
        val catalog = providerChainCatalog()
        val configuration = chainConfiguration(catalog).let { snapshot ->
            snapshot.copy(externalProviderConsents = snapshot.externalProviderConsents.filterNot { it.providerId == "crossref" })
        }
        val cache = RecordingCrossrefLookupCache(doiHit = listOf(ScholarlyWork("10.1234/consent", "Cached", emptyList(), null)))
        val builder = RestClient.builder().baseUrl("https://api.crossref.org")
        val server = MockRestServiceServer.bindTo(builder).build()
        server.expect(ExpectedCount.never(), requestTo(containsString("https://api.crossref.org")))
        val lookup = CrossrefScholarlyMetadataLookup(builder.build(), ProviderCallGate(catalog), configuration, null, cache)

        assertThrows(ProviderCallRejectedException::class.java) { lookup.byDoi("10.1234/consent") }

        assertEquals(emptyList<String>(), cache.doiReads)
        assertEquals(emptyList<Pair<String, List<ScholarlyWork>>>(), cache.doiWrites)
        server.verify()
    }

    @Test
    fun `legacy scalar Crossref lookups retain DOI and search cache hits`() {
        val cachedDoi = ScholarlyWork("10.1234/legacy-doi", "Cached DOI study", emptyList(), null)
        val doiCache = RecordingCrossrefLookupCache(doiHit = listOf(cachedDoi))
        val doiBuilder = RestClient.builder().baseUrl("https://api.crossref.org")
        val doiServer = MockRestServiceServer.bindTo(doiBuilder).build()
        doiServer.expect(ExpectedCount.never(), requestTo(containsString("https://api.crossref.org")))
        val doiLookup = CrossrefScholarlyMetadataLookup(
            doiBuilder.build(), gate, crossrefConfiguration(), null, doiCache,
        )

        assertEquals(cachedDoi, doiLookup.byDoi("10.1234/legacy-doi"))
        assertEquals(listOf("10.1234/legacy-doi"), doiCache.doiReads)
        assertEquals(emptyList<Pair<String, List<ScholarlyWork>>>(), doiCache.doiWrites)
        doiServer.verify()

        val cachedSearch = ScholarlyWork("10.1234/legacy-search", "Cached search study", emptyList(), null)
        val searchCache = RecordingCrossrefLookupCache(searchHit = listOf(cachedSearch))
        val searchBuilder = RestClient.builder().baseUrl("https://api.crossref.org")
        val searchServer = MockRestServiceServer.bindTo(searchBuilder).build()
        searchServer.expect(ExpectedCount.never(), requestTo(containsString("https://api.crossref.org")))
        val searchLookup = CrossrefScholarlyMetadataLookup(
            searchBuilder.build(), gate, crossrefConfiguration(), null, searchCache,
        )

        assertEquals(listOf(cachedSearch), searchLookup.search(BibliographyReference("A test title", listOf("Ada Researcher"), 2024, null, "JOURNAL_ARTICLE")))
        assertEquals(listOf("A test title Ada Researcher 2024"), searchCache.searchReads)
        assertEquals(emptyList<Pair<String, List<ScholarlyWork>>>(), searchCache.searchWrites)
        searchServer.verify()
    }

    @Test
    fun `legacy scalar cache misses still fetch and store Crossref DOI and search results`() {
        val cache = RecordingCrossrefLookupCache()
        val builder = RestClient.builder().baseUrl("https://api.crossref.org")
        val server = MockRestServiceServer.bindTo(builder).build()
        server.expect(requestTo("https://api.crossref.org/works/10.1234/legacy-miss"))
            .andExpect(method(HttpMethod.GET))
            .andRespond(withSuccess(
                """{"message":{"DOI":"10.1234/legacy-miss","title":["Legacy DOI result"],"author":[]}}""",
                MediaType.APPLICATION_JSON,
            ))
        server.expect(requestTo("https://api.crossref.org/works?query.bibliographic=Legacy%20title%20Ada%20Researcher%202024&rows=10"))
            .andExpect(method(HttpMethod.GET))
            .andRespond(withSuccess(
                """{"message":{"items":[{"DOI":"10.1234/legacy-search-miss","title":["Legacy title"],"author":[]}]}}""",
                MediaType.APPLICATION_JSON,
            ))
        val lookup = CrossrefScholarlyMetadataLookup(builder.build(), gate, crossrefConfiguration(), null, cache)

        val doiResult = lookup.byDoi("10.1234/legacy-miss")
        val searchResults = lookup.search(BibliographyReference("Legacy title", listOf("Ada Researcher"), 2024, null, "JOURNAL_ARTICLE"))

        assertEquals("Legacy DOI result", doiResult?.title)
        assertEquals("Legacy title", searchResults.single().title)
        assertEquals(listOf("10.1234/legacy-miss"), cache.doiReads)
        assertEquals(listOf("10.1234/legacy-miss" to listOf(requireNotNull(doiResult))), cache.doiWrites)
        assertEquals(listOf("Legacy title Ada Researcher 2024"), cache.searchReads)
        assertEquals(listOf("Legacy title Ada Researcher 2024" to searchResults), cache.searchWrites)
        server.verify()
    }

    @Test
    fun `contact email is separately consented before Crossref can dispatch a request`() {
        val contactEmail = "operator@example.invalid"
        val catalog = configuredExternalProviderCatalogWithContactEmail(contactEmail)
        val configurationFactory = RunConfigurationFactory(
            providerCatalog = catalog,
            parserId = "grobid",
            parserVersion = "0.9.1-crf",
            languageDetectorVersion = "0.6",
            limits = ValidationLimitsSnapshot(1_000_000, 20, 100_000, 100_000, 100, 0.65),
        )
        val fullyConsented = configurationFactory.from(
            RunConfigurationRequest(
                scholarlyMetadataProvider = "crossref",
                externalProviderConsents = listOf(
                    externalProviderConsent(catalog, "crossref", listOf("bibliographic_metadata", "provider_contact_email")),
                ),
            ),
        )
        val missingEmailConsent = fullyConsented.copy(
            externalProviderConsents = listOf(
                fullyConsented.externalProviderConsents.single().copy(dataCategories = listOf("bibliographic_metadata")),
            ),
        )
        val builder = RestClient.builder().baseUrl("https://api.crossref.org")
        val server = MockRestServiceServer.bindTo(builder).build()
        server.expect(ExpectedCount.never(), requestTo(containsString("api.crossref.org")))
        val lookup = CrossrefScholarlyMetadataLookup(
            builder.build(),
            ProviderCallGate(catalog),
            missingEmailConsent,
            contactEmail,
            NoOpCrossrefLookupCache,
        )

        assertThrows(ProviderCallRejectedException::class.java) {
            lookup.search(BibliographyReference("A test title", listOf("A Researcher"), 2024, null, "JOURNAL_ARTICLE"))
        }

        server.verify()
    }

    @Test
    fun `rejects changed Crossref contact email after the run is configured without making a request`() {
        val originalEmail = "original@example.invalid"
        val changedEmail = "changed@example.invalid"
        val originalCatalog = configuredExternalProviderCatalogWithContactEmail(originalEmail)
        val configuration = RunConfigurationFactory(
            providerCatalog = originalCatalog,
            parserId = "grobid",
            parserVersion = "0.9.1-crf",
            languageDetectorVersion = "0.6",
            limits = ValidationLimitsSnapshot(1_000_000, 20, 100_000, 100_000, 100, 0.65),
        ).from(
            RunConfigurationRequest(
                scholarlyMetadataProvider = "crossref",
                externalProviderConsents = listOf(
                    externalProviderConsent(originalCatalog, "crossref", listOf("bibliographic_metadata", "provider_contact_email")),
                ),
            ),
        )
        val builder = RestClient.builder().baseUrl("https://api.crossref.org")
        val server = MockRestServiceServer.bindTo(builder).build()
        server.expect(ExpectedCount.never(), requestTo(containsString("api.crossref.org")))
        val lookup = CrossrefScholarlyMetadataLookup(
            builder.build(),
            ProviderCallGate(configuredExternalProviderCatalogWithContactEmail(changedEmail)),
            configuration,
            changedEmail,
            NoOpCrossrefLookupCache,
        )

        assertThrows(ProviderCallRejectedException::class.java) {
            lookup.search(BibliographyReference("A test title", listOf("A Researcher"), 2024, null, "JOURNAL_ARTICLE"))
        }

        server.verify()
    }

    private fun chainExecutionTrace(client: RestClient, catalog: ProviderCatalog): ChainExecutionTrace {
        val configuration = chainConfiguration(catalog)
        val repository = Mockito.mock(AnalysisRunExecutionRepository::class.java)
        val runId = UUID.randomUUID()
        val eventId = UUID.randomUUID()
        val parent = ExecutionSpanSpec("references", "INTERNAL", "Resolve bibliography entry", eventId = eventId)
        val parentHandle = ExecutionSpanHandle(
            UUID.randomUUID(), runId, UUID.randomUUID(), Instant.now(), System.nanoTime(),
            stageId = "references", eventId = eventId,
        )
        val providerSpec = ExecutionSpanSpec(
            stageId = "references",
            kind = "PROVIDER",
            name = "Crossref scholarly metadata request",
            eventId = eventId,
            parentSpanId = parentHandle.id,
            providerId = "crossref",
            operationId = ExecutionOperationId.forEvent(eventId, "provider-call:crossref-doi-lookup"),
            attributes = mapOf("httpRoute" to "/works"),
        )
        val providerHandle = ExecutionSpanHandle(
            UUID.randomUUID(), runId, providerSpec.operationId!!, Instant.now(), System.nanoTime(), eventId = eventId,
        )
        Mockito.`when`(repository.startSpan(runId, parent, "{}")).thenReturn(parentHandle)
        Mockito.`when`(repository.startSpan(runId, providerSpec, """{"httpRoute":"/works"}""")).thenReturn(providerHandle)
        val execution = AnalysisRunExecutionService(repository, ExecutionCaptureSanitizer())
        val lookup = CrossrefScholarlyMetadataLookup(
            client = client,
            callGate = ProviderCallGate(catalog),
            configuration = configuration,
            contactEmail = null,
            cache = NoOpCrossrefLookupCache,
            executionService = execution,
        )
        return ChainExecutionTrace(repository, execution, runId, parent, lookup)
    }

    private data class ChainExecutionTrace(
        private val repository: AnalysisRunExecutionRepository,
        private val execution: AnalysisRunExecutionService,
        private val runId: UUID,
        private val parent: ExecutionSpanSpec,
        val lookup: CrossrefScholarlyMetadataLookup,
    ) {
        fun <T> record(operation: () -> T): T = execution.record(runId, parent, operation)

        fun artifacts(): List<Pair<ExecutionSpanArtifactSpec, SanitizedExecutionArtifact>> =
            Mockito.mockingDetails(repository).invocations
                .filter { it.method.name == "recordArtifact" }
                .map {
                    it.arguments[2] as ExecutionSpanArtifactSpec to
                        it.arguments[3] as SanitizedExecutionArtifact
                }
    }

    private fun disconnectingStream(): InputStream = object : InputStream() {
        private var firstBytePending = true

        override fun read(): Int {
            if (firstBytePending) {
                firstBytePending = false
                return '{'.code
            }
            throw IOException("Synthetic response disconnect.")
        }
    }

    private fun crossrefResponseOfSize(targetBytes: Int, doi: String, title: String): String {
        val prefix = """{"message":{"DOI":"$doi","title":["$title"],"author":[],"abstract":""" + "\""
        val suffix = "\"}}"
        val fillerLength = targetBytes - prefix.length - suffix.length
        require(fillerLength >= 0)
        return prefix + "x".repeat(fillerLength) + suffix
    }

    private fun providerChainCatalog(contactEmail: String? = null) = ProviderCatalog.safeDefaults(
        providerChainEnabled = true,
        crossrefEnabled = true,
        crossrefRetentionDisclosure = "Crossref exact DOI chain test disclosure.",
        crossrefContactEmail = contactEmail,
        dataCiteEnabled = true,
        dataCiteRetentionDisclosure = "DataCite exact DOI chain test disclosure.",
    )

    private fun chainConfiguration(catalog: ProviderCatalog) = configurationFactoryFor(catalog).from(
        RunConfigurationRequest(
            providerChainId = ProviderChainSnapshot.CHAIN_ID,
            scholarlyMetadataProvider = null,
            externalProviderConsents = ProviderChainSnapshot.PROVIDER_IDS.map { providerId ->
                val registration = catalog.requireProviderChain(ProviderChainSnapshot.CHAIN_ID)
                    .single { it.providerId == providerId }
                ExternalProviderConsentRequest(
                    providerId = providerId,
                    dataCategories = registration.dataCategories.map(DataCategory::id).sorted(),
                    retentionDisclosureFingerprint = requireNotNull(registration.consentDisclosureFingerprint()),
                )
            },
        ),
    )

    private fun configurationFactoryFor(catalog: ProviderCatalog) = RunConfigurationFactory(
        providerCatalog = catalog,
        parserId = "grobid",
        parserVersion = "0.9.1-crf",
        languageDetectorVersion = "0.6",
        limits = ValidationLimitsSnapshot(1_000_000, 20, 100_000, 100_000, 100, 0.65),
    )

    private class RecordingCrossrefLookupCache(
        private val doiHit: List<ScholarlyWork>? = null,
        private val searchHit: List<ScholarlyWork>? = null,
    ) : CrossrefLookupCache {
        val doiReads = mutableListOf<String>()
        val doiWrites = mutableListOf<Pair<String, List<ScholarlyWork>>>()
        val searchReads = mutableListOf<String>()
        val searchWrites = mutableListOf<Pair<String, List<ScholarlyWork>>>()

        override fun findByDoi(doi: String): List<ScholarlyWork>? {
            doiReads += doi
            return doiHit
        }

        override fun storeByDoi(doi: String, works: List<ScholarlyWork>) {
            doiWrites += doi to works
        }

        override fun findBySearch(query: String): List<ScholarlyWork>? {
            searchReads += query
            return searchHit
        }

        override fun storeSearch(query: String, works: List<ScholarlyWork>) {
            searchWrites += query to works
        }

        override fun invalidateDoi(doi: String) = false

        override fun invalidateSearch(query: String) = false
    }

    private fun configuredExternalProviderCatalogWithContactEmail(contactEmail: String) =
        ProviderCatalog.safeDefaults(
            crossrefEnabled = true,
            crossrefRetentionDisclosure = "Reviewed test retention disclosure.",
            crossrefContactEmail = contactEmail,
        )

    private fun crossrefConfiguration() = runConfigurationFactory.from(
        RunConfigurationRequest(
            scholarlyMetadataProvider = "crossref",
            externalProviderConsents = listOf(
                externalProviderConsent(providerCatalog, "crossref", listOf(DataCategory.BIBLIOGRAPHIC_METADATA.id)),
            ),
        ),
    )

    companion object {
        private const val MAX_CHAIN_JSON_BYTES = 1_048_576
    }
}
