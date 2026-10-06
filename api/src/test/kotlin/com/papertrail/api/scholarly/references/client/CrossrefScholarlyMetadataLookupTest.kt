package com.papertrail.api.scholarly.references.client

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.papertrail.api.analysis.execution.ExecutionCaptureSanitizer
import com.papertrail.api.infrastructure.providers.DataCategory
import com.papertrail.api.infrastructure.providers.ProviderCallGate
import com.papertrail.api.infrastructure.providers.ProviderCallRejectedException
import com.papertrail.api.infrastructure.providers.ProviderCatalog
import com.papertrail.api.infrastructure.providers.externalProviderConsent
import com.papertrail.api.infrastructure.providers.configuredExternalProviderCatalog
import com.papertrail.api.analysis.configuration.RunConfigurationFactory
import com.papertrail.api.analysis.configuration.ValidationLimitsSnapshot
import com.papertrail.api.analysis.http.RunConfigurationRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.web.client.ExpectedCount
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import org.hamcrest.Matchers.containsString

class CrossrefScholarlyMetadataLookupTest {
    private val objectMapper = jacksonObjectMapper()
    private val providerCatalog = configuredExternalProviderCatalog()
    private val gate = ProviderCallGate(providerCatalog)
    private val runConfigurationFactory = RunConfigurationFactory(
        objectMapper = objectMapper,
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
        val lookup = CrossrefScholarlyMetadataLookup(builder.build(), objectMapper, gate, configuration, null, NoOpCrossrefLookupCache)

        assertThrows(ProviderCallRejectedException::class.java) {
            lookup.byDoi("10.1234/unconsented")
        }

        server.verify()
    }

    @Test
    fun `consented DOI lookup sends the request and returns Crossref metadata`() {
        val builder = RestClient.builder().baseUrl("https://api.crossref.org")
        val server = MockRestServiceServer.bindTo(builder).build()
        server.expect(requestTo(containsString("/works/10.1234")))
            .andExpect(method(HttpMethod.GET))
            .andRespond(withSuccess(
                """{"message":{"DOI":"10.1234/confirmed","title":["Confirmed study"],"author":[{"given":"Ada","family":"Researcher"}],"published-print":{"date-parts":[[2024]]}}}""",
                MediaType.APPLICATION_JSON,
            ))
        val lookup = CrossrefScholarlyMetadataLookup(builder.build(), objectMapper, gate, crossrefConfiguration(), null, NoOpCrossrefLookupCache)

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
        message.put("abstract", "x".repeat(ExecutionCaptureSanitizer.DEFAULT_MAX_ARTIFACT_BYTES + 1))
        val builder = RestClient.builder().baseUrl("https://api.crossref.org")
        val server = MockRestServiceServer.bindTo(builder).build()
        server.expect(requestTo(containsString("/works/10.1234")))
            .andExpect(method(HttpMethod.GET))
            .andRespond(withSuccess(objectMapper.writeValueAsString(response), MediaType.APPLICATION_JSON))
        val lookup = CrossrefScholarlyMetadataLookup(builder.build(), objectMapper, gate, crossrefConfiguration(), null, NoOpCrossrefLookupCache)

        val work = lookup.byDoi("10.1234/large-response")

        assertEquals("10.1234/large-response", work?.doi)
        assertEquals("Large response study", work?.title)
        server.verify()
    }

    @Test
    fun `metadata search sends only the bibliography query and parses returned works`() {
        val builder = RestClient.builder().baseUrl("https://api.crossref.org")
        val server = MockRestServiceServer.bindTo(builder).build()
        server.expect(requestTo(containsString("/works?")))
            .andExpect(method(HttpMethod.GET))
            .andExpect(queryParam("query.bibliographic", "A%20test%20title%20Ada%20Researcher%202024"))
            .andRespond(withSuccess(
                """{"message":{"items":[{"DOI":"10.1234/search-match","title":["A test title"],"author":[{"given":"Ada","family":"Researcher"}],"issued":{"date-parts":[[2024]]}}]}}""",
                MediaType.APPLICATION_JSON,
            ))
        val lookup = CrossrefScholarlyMetadataLookup(builder.build(), objectMapper, gate, crossrefConfiguration(), null, NoOpCrossrefLookupCache)

        val matches = lookup.search(BibliographyReference("A test title", listOf("Ada Researcher"), 2024, null, "JOURNAL_ARTICLE"))

        assertEquals(1, matches.size)
        assertEquals("10.1234/search-match", matches.single().doi)
        assertEquals("A test title", matches.single().title)
        server.verify()
    }

    @Test
    fun `contact email is separately consented before Crossref can dispatch a request`() {
        val contactEmail = "operator@example.invalid"
        val catalog = configuredExternalProviderCatalogWithContactEmail(contactEmail)
        val configurationFactory = RunConfigurationFactory(
            objectMapper = objectMapper,
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
            objectMapper,
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
            objectMapper = objectMapper,
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
            objectMapper,
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
}
