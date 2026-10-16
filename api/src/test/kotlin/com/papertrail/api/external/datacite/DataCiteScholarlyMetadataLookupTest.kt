package com.papertrail.api.external.datacite

import com.papertrail.api.analysis.configuration.AnalysisConfigurationSnapshot
import com.papertrail.api.analysis.configuration.ProviderChainSnapshot
import com.papertrail.api.analysis.configuration.RunConfigurationFactory
import com.papertrail.api.analysis.configuration.ValidationLimitsSnapshot
import com.papertrail.api.analysis.http.ExternalProviderConsentRequest
import com.papertrail.api.analysis.http.RunConfigurationRequest
import com.papertrail.api.infrastructure.providers.DataCategory
import com.papertrail.api.infrastructure.providers.ProviderCallGate
import com.papertrail.api.infrastructure.providers.ProviderCallRejectedException
import com.papertrail.api.infrastructure.providers.ProviderCatalog
import com.papertrail.api.scholarly.references.attempts.domain.ReferenceResolutionAttemptOutcomeCode
import com.papertrail.api.scholarly.references.client.BibliographyReference
import com.papertrail.api.scholarly.references.client.ScholarlyWork
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.mock.http.client.MockClientHttpResponse
import org.springframework.test.web.client.ExpectedCount
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.header
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import java.io.IOException
import java.io.InputStream
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class DataCiteScholarlyMetadataLookupTest {
    @Test
    fun `exact normalized DOI lookup encodes the path and maps safe metadata without contact email`() {
        val fixture = fixture()
        expectExactLookupRequest(fixture.server, "10.1234%2Fexact-doi")
            .andRespond(withSuccess(
                """{"data":{"id":"10.1234/exact-doi","type":"dois","attributes":{"doi":"HTTPS://DOI.ORG/10.1234/EXACT-DOI","titles":[{"title":"  Confirmed   study "}],"creators":[{"name":"Ada Researcher"},{"givenName":"Grace","familyName":"Hopper"}],"publicationYear":2024}}}""",
                DATA_CITE_MEDIA_TYPE,
            ))
        val lookup = lookup(fixture)

        val work = lookup.byDoi("doi: 10.1234/Exact-DOI")

        assertEquals("10.1234/exact-doi", work?.doi)
        assertEquals("Confirmed study", work?.title)
        assertEquals(listOf("Ada Researcher", "Grace Hopper"), work?.authors)
        assertEquals(2024, work?.year)
        fixture.server.verify()
    }

    @Test
    fun `returns a mismatched DOI response to the conservative resolver instead of hiding it as a miss`() {
        val fixture = fixture()
        expectExactLookupRequest(fixture.server, "10.1234%2Frequested-doi")
            .andRespond(withSuccess(
                response(doi = "https://doi.org/10.1234/different-doi"),
                DATA_CITE_MEDIA_TYPE,
            ))

        val work = lookup(fixture).byDoi("10.1234/Requested-DOI")

        assertEquals("10.1234/different-doi", work?.doi)
        assertEquals("Safe study", work?.title)
        fixture.server.verify()
    }

    @Test
    fun `invalid DOI does not make a provider request`() {
        val fixture = fixture()
        expectNoDataCiteRequest(fixture.server)

        val work = lookup(fixture).byDoi("not a DOI / private input")

        assertNull(work)
        fixture.server.verify()
    }

    @Test
    fun `404 is a no-record result while transient and permanent HTTP failures are safe failures`() {
        val fixture = fixture()
        expectExactLookupRequest(fixture.server, "10.1234%2Flookup-failure")
            .andRespond(withStatus(HttpStatus.NOT_FOUND).body(SECRET_BODY))
        expectExactLookupRequest(fixture.server, "10.1234%2Flookup-failure")
            .andRespond(
                withStatus(HttpStatus.SERVICE_UNAVAILABLE).body(SECRET_BODY)
                    .header(HttpHeaders.RETRY_AFTER, "Wed, 21 Oct 2015 07:28:00 GMT"),
            )
        expectExactLookupRequest(fixture.server, "10.1234%2Flookup-failure")
            .andRespond(withStatus(HttpStatus.NOT_IMPLEMENTED).body(SECRET_BODY).header(HttpHeaders.RETRY_AFTER, "2"))
        expectExactLookupRequest(fixture.server, "10.1234%2Flookup-failure")
            .andRespond(withStatus(HttpStatus.HTTP_VERSION_NOT_SUPPORTED).body(SECRET_BODY).header(HttpHeaders.RETRY_AFTER, "2"))
        expectExactLookupRequest(fixture.server, "10.1234%2Flookup-failure")
            .andRespond(withStatus(HttpStatus.BAD_REQUEST).body(SECRET_BODY))
        val lookup = lookup(fixture)

        assertNull(lookup.byDoi(REQUEST_DOI))
        val retryable = assertSafeFailure { lookup.byDoi(REQUEST_DOI) }
        assertEquals(ReferenceResolutionAttemptOutcomeCode.RETRYABLE_FAILURE, retryable.outcomeCode)
        assertEquals(503, retryable.httpStatus)
        assertEquals(60_000, retryable.retryAfterMillis)
        val notImplemented = assertSafeFailure { lookup.byDoi(REQUEST_DOI) }
        assertEquals(ReferenceResolutionAttemptOutcomeCode.PERMANENT_FAILURE, notImplemented.outcomeCode)
        assertEquals(501, notImplemented.httpStatus)
        assertNull(notImplemented.retryAfterMillis)
        val unsupportedVersion = assertSafeFailure { lookup.byDoi(REQUEST_DOI) }
        assertEquals(ReferenceResolutionAttemptOutcomeCode.PERMANENT_FAILURE, unsupportedVersion.outcomeCode)
        assertEquals(505, unsupportedVersion.httpStatus)
        val permanent = assertSafeFailure { lookup.byDoi(REQUEST_DOI) }
        assertEquals(ReferenceResolutionAttemptOutcomeCode.PERMANENT_FAILURE, permanent.outcomeCode)
        assertEquals(400, permanent.httpStatus)
        fixture.server.verify()
    }

    @Test
    fun `oversized and malformed responses fail without exposing provider content`() {
        val fixture = fixture()
        val exactLimit = response(REQUEST_DOI).padEnd(1_048_576, ' ')
        val oversized = " ".repeat(1_048_577) + response(REQUEST_DOI)
        val trailingToken = response(REQUEST_DOI) + " {}"
        val duplicateDoi = """{"data":{"id":"$REQUEST_DOI","type":"dois","attributes":{"doi":"10.1234/other","doi":"$REQUEST_DOI","titles":[{"title":"Safe study"}],"creators":[],"publicationYear":2024}}}"""
        expectExactLookupRequest(fixture.server, "10.1234%2Flookup-failure")
            .andRespond(withSuccess(exactLimit, DATA_CITE_MEDIA_TYPE))
        expectExactLookupRequest(fixture.server, "10.1234%2Flookup-failure")
            .andRespond(withSuccess(oversized, DATA_CITE_MEDIA_TYPE))
        expectExactLookupRequest(fixture.server, "10.1234%2Flookup-failure")
            .andRespond(withSuccess(SECRET_BODY, DATA_CITE_MEDIA_TYPE))
        expectExactLookupRequest(fixture.server, "10.1234%2Flookup-failure")
            .andRespond(withSuccess(trailingToken, DATA_CITE_MEDIA_TYPE))
        expectExactLookupRequest(fixture.server, "10.1234%2Flookup-failure")
            .andRespond(withSuccess(duplicateDoi, DATA_CITE_MEDIA_TYPE))
        val lookup = lookup(fixture)

        assertEquals(REQUEST_DOI, lookup.byDoi(REQUEST_DOI)?.doi)
        val oversizedFailure = assertSafeFailure { lookup.byDoi(REQUEST_DOI) }
        assertEquals(200, oversizedFailure.httpStatus)
        assertEquals(1_048_577L, oversizedFailure.responseBytes)
        val malformedFailure = assertSafeFailure { lookup.byDoi(REQUEST_DOI) }
        assertEquals(200, malformedFailure.httpStatus)
        assertEquals(SECRET_BODY.toByteArray().size.toLong(), malformedFailure.responseBytes)
        assertSafeFailure { lookup.byDoi(REQUEST_DOI) }
        assertSafeFailure { lookup.byDoi(REQUEST_DOI) }
        fixture.server.verify()
    }

    @Test
    fun `response stream IOException is retryable and does not trust declared encoded bytes`() {
        val fixture = fixture()
        expectExactLookupRequest(fixture.server, "10.1234%2Flookup-failure").andRespond {
            MockClientHttpResponse(disconnectingStream(), HttpStatus.OK).apply {
                headers.contentType = DATA_CITE_MEDIA_TYPE
                headers.contentLength = 1_234
            }
        }

        val failure = assertSafeFailure { lookup(fixture).byDoi(REQUEST_DOI) }

        assertEquals(ReferenceResolutionAttemptOutcomeCode.RETRYABLE_FAILURE, failure.outcomeCode)
        assertEquals(200, failure.httpStatus)
        assertNull(failure.responseBytes)
        fixture.server.verify()
    }

    @Test
    fun `unsupported content encoding and redirects fail safely`() {
        val fixture = fixture()
        expectExactLookupRequest(fixture.server, "10.1234%2Flookup-failure").andRespond(
            withSuccess(response(REQUEST_DOI), DATA_CITE_MEDIA_TYPE)
                .header(HttpHeaders.CONTENT_ENCODING, "gzip")
                .header(HttpHeaders.CONTENT_LENGTH, "1234"),
        )
        expectExactLookupRequest(fixture.server, "10.1234%2Flookup-failure").andRespond(
            withSuccess(response(REQUEST_DOI), DATA_CITE_MEDIA_TYPE)
                .header(HttpHeaders.CONTENT_ENCODING, "identity", "gzip"),
        )
        expectExactLookupRequest(fixture.server, "10.1234%2Flookup-failure").andRespond(
            withStatus(HttpStatus.FOUND).header(HttpHeaders.LOCATION, "$BASE_URL/elsewhere"),
        )
        val lookup = lookup(fixture)

        val unsupportedEncoding = assertSafeFailure { lookup.byDoi(REQUEST_DOI) }
        assertEquals(200, unsupportedEncoding.httpStatus)
        assertNull(unsupportedEncoding.responseBytes)
        assertSafeFailure { lookup.byDoi(REQUEST_DOI) }
        assertSafeFailure { lookup.byDoi(REQUEST_DOI) }
        fixture.server.verify()
    }

    @Test
    fun `missing DataCite consent rejects a fixed chain DOI lookup before HTTP`() {
        val fixture = fixture()
        expectNoDataCiteRequest(fixture.server)
        val configuration = configuration()
        val crossrefOnlyConsents = configuration.externalProviderConsents.filterNot {
            it.providerId == DataCiteScholarlyMetadataLookup.PROVIDER_ID
        }

        assertEquals(listOf("crossref"), crossrefOnlyConsents.map { it.providerId })
        assertThrows(ProviderCallRejectedException::class.java) {
            lookup(fixture, configuration.copy(externalProviderConsents = crossrefOnlyConsents)).byDoi(REQUEST_DOI)
        }

        fixture.server.verify()
    }

    @Test
    fun `provider-chain pre-send callback runs after authorization and before DataCite HTTP`() {
        val fixture = fixture()
        var beforeWire = false
        val responseBody = response(REQUEST_DOI)
        expectExactLookupRequest(fixture.server, "10.1234%2Flookup-failure")
            .andRespond { request ->
                assertTrue(beforeWire, "The attempt must start after provider authorization and before HTTP.")
                withSuccess(responseBody, DATA_CITE_MEDIA_TYPE).createResponse(request)
            }

        val result = lookup(fixture).byDoiForChain(REQUEST_DOI) { beforeWire = true }

        assertEquals(REQUEST_DOI, result.value?.doi)
        assertEquals(200, result.httpStatus)
        assertEquals(responseBody.toByteArray().size.toLong(), result.responseBytes)
        fixture.server.verify()
    }

    @Test
    fun `DataCite consent rejection does not invoke the pre-send callback`() {
        val fixture = fixture()
        expectNoDataCiteRequest(fixture.server)
        var beforeWire = false
        val noDataCiteConsent = configuration().copy(
            externalProviderConsents = configuration().externalProviderConsents
                .filterNot { it.providerId == DataCiteScholarlyMetadataLookup.PROVIDER_ID },
        )

        assertThrows(ProviderCallRejectedException::class.java) {
            lookup(fixture, noDataCiteConsent).byDoiForChain(REQUEST_DOI) { beforeWire = true }
        }

        assertFalse(beforeWire)
        fixture.server.verify()
    }

    @Test
    fun `search never contacts DataCite`() {
        val fixture = fixture()
        expectNoDataCiteRequest(fixture.server)
        val lookup = lookup(fixture, configuration().copy(externalProviderConsents = emptyList()))

        val results = lookup.search(BibliographyReference("Private title", listOf("A Researcher"), 2024, null, "JOURNAL_ARTICLE"))

        assertEquals(emptyList<ScholarlyWork>(), results)
        fixture.server.verify()
    }

    private fun assertSafeFailure(operation: () -> Unit): DataCiteProviderException {
        val exception = assertThrows(DataCiteProviderException::class.java) { operation() }
        assertEquals("DataCite scholarly metadata lookup failed.", exception.message)
        assertNull(exception.cause)
        assertFalse(exception.message.orEmpty().contains(REQUEST_DOI))
        assertFalse(exception.message.orEmpty().contains(SECRET_BODY))
        return exception
    }

    private fun response(doi: String): String =
        """{"data":{"id":"$REQUEST_DOI","type":"dois","attributes":{"doi":"$doi","titles":[{"title":"Safe study"}],"creators":[],"publicationYear":2024}}}"""

    private fun lookup(fixture: HttpFixture, configuration: AnalysisConfigurationSnapshot = configuration()) =
        DataCiteScholarlyMetadataLookup(
            fixture.client,
            ProviderCallGate(providerCatalog),
            configuration,
            Clock.fixed(FIXED_NOW, ZoneOffset.UTC),
        )

    private fun expectExactLookupRequest(server: MockRestServiceServer, encodedDoi: String) =
        server.expect(requestTo("$BASE_URL/dois/$encodedDoi"))
            .andExpect(method(HttpMethod.GET))
            .andExpect(header(HttpHeaders.ACCEPT, DATA_CITE_MEDIA_TYPE.toString()))
            .andExpect(header(HttpHeaders.ACCEPT_ENCODING, "identity"))

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

    private fun expectNoDataCiteRequest(server: MockRestServiceServer) {
        server.expect(ExpectedCount.never(), requestTo(containsString(BASE_URL)))
    }

    private fun fixture(): HttpFixture {
        val builder = RestClient.builder().baseUrl(BASE_URL)
        val server = MockRestServiceServer.bindTo(builder).build()
        return HttpFixture(builder.build(), server)
    }

    private fun configuration(): AnalysisConfigurationSnapshot {
        val snapshot = configurationFactory.from(
            RunConfigurationRequest(
                providerChainId = ProviderChainSnapshot.CHAIN_ID,
                scholarlyMetadataProvider = null,
                externalProviderConsents = listOf(
                    providerChainConsent(ProviderChainSnapshot.CROSSREF_PROVIDER_ID),
                    providerChainConsent(DataCiteScholarlyMetadataLookup.PROVIDER_ID),
                ),
            ),
        )
        assertNull(snapshot.referenceResolution.provider)
        assertEquals(ProviderChainSnapshot.CHAIN_ID, snapshot.referenceResolution.providerChain?.chainId)
        assertEquals(ProviderChainSnapshot.PROVIDER_IDS, snapshot.referenceResolution.providerChain?.providers?.map { it.provider })
        assertEquals(ProviderChainSnapshot.PROVIDER_IDS, snapshot.externalProviderConsents.map { it.providerId })
        assertEquals(
            listOf(DataCategory.BIBLIOGRAPHIC_METADATA.id),
            snapshot.externalProviderConsents.single { it.providerId == DataCiteScholarlyMetadataLookup.PROVIDER_ID }.dataCategories,
        )
        return snapshot
    }

    private fun providerChainConsent(providerId: String): ExternalProviderConsentRequest {
        val registration = providerCatalog.requireProviderChain(ProviderChainSnapshot.CHAIN_ID)
            .single { it.providerId == providerId }
        return ExternalProviderConsentRequest(
            providerId = providerId,
            dataCategories = registration.dataCategories.map(DataCategory::id).sorted(),
            retentionDisclosureFingerprint = requireNotNull(registration.consentDisclosureFingerprint()),
        )
    }

    private data class HttpFixture(val client: RestClient, val server: MockRestServiceServer)

    companion object {
        private const val BASE_URL = "https://api.datacite.org"
        private const val REQUEST_DOI = "10.1234/lookup-failure"
        private val FIXED_NOW = Instant.parse("2015-10-21T07:27:00Z")
        private const val SECRET_BODY = "provider-private-response-body"
        private val DATA_CITE_MEDIA_TYPE = MediaType.parseMediaType("application/vnd.api+json")
        private val providerCatalog = ProviderCatalog.safeDefaults(
            providerChainEnabled = true,
            crossrefEnabled = true,
            crossrefRetentionDisclosure = "Crossref public API disclosure for the controlled test fixture.",
            dataCiteEnabled = true,
            dataCiteRetentionDisclosure = "DataCite public API disclosure for the controlled test fixture.",
        )
        private val configurationFactory = RunConfigurationFactory(
            providerCatalog = providerCatalog,
            parserId = "grobid",
            parserVersion = "0.9.1-crf",
            languageDetectorVersion = "0.6",
            limits = ValidationLimitsSnapshot(1_000_000, 20, 100_000, 100_000, 100, 0.65),
        )
    }
}
