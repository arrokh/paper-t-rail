package com.papertrail.api.scholarly.acquisition.client

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.papertrail.api.analysis.configuration.ExternalProviderConsentSnapshot
import com.papertrail.api.analysis.configuration.RunConfigurationFactory
import com.papertrail.api.analysis.configuration.ValidationLimitsSnapshot
import com.papertrail.api.analysis.http.RunConfigurationRequest
import com.papertrail.api.infrastructure.providers.ProviderCallGate
import com.papertrail.api.infrastructure.providers.ProviderCallRejectedException
import com.papertrail.api.infrastructure.providers.ProviderCatalog
import com.papertrail.api.scholarly.acquisition.domain.OpenAccessLocation
import com.papertrail.api.scholarly.references.client.BibliographyReference
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.client.ExpectedCount
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import org.hamcrest.Matchers.containsString

class UnpaywallOpenAccessProviderFactoryTest {
    private val objectMapper = jacksonObjectMapper()
    private val contactEmail = "researcher@example.invalid"
    private val catalog = ProviderCatalog.safeDefaults(
        unpaywallEnabled = true,
        unpaywallEnablementReviewed = true,
        unpaywallRetentionDisclosure = "Reviewed Unpaywall terms for this controlled-provider test.",
        unpaywallContactEmail = contactEmail,
    )
    private val factory = RunConfigurationFactory(
        objectMapper = objectMapper,
        providerCatalog = catalog,
        parserId = "grobid",
        parserVersion = "0.9.1-crf",
        languageDetectorVersion = "0.6",
        limits = ValidationLimitsSnapshot(1_000_000, 20, 100_000, 100_000, 100, 0.65),
    )

    @Test
    fun `run snapshot pins Unpaywall settings without persisting the contact email`() {
        val configuration = configuredRun()

        assertNotNull(configuration.openAccessProviderConfigurationFingerprint)
        assertFalse(configuration.openAccessProviderConfigurationFingerprint!!.contains(contactEmail))
        assertEquals(
            "Reviewed Unpaywall terms for this controlled-provider test.",
            configuration.openAccessRetentionDisclosure,
        )
    }

    @Test
    fun `changed Unpaywall contact settings reject requests pinned to an earlier run`() {
        val changedCatalog = ProviderCatalog.safeDefaults(
            unpaywallEnabled = true,
            unpaywallEnablementReviewed = true,
            unpaywallRetentionDisclosure = "Reviewed Unpaywall terms for this controlled-provider test.",
            unpaywallContactEmail = "changed@example.invalid",
        )
        val discoveryBuilder = RestClient.builder().baseUrl("https://api.unpaywall.org")
        val server = MockRestServiceServer.bindTo(discoveryBuilder).build()
        server.expect(ExpectedCount.never(), requestTo(containsString("api.unpaywall.org")))
        val provider = UnpaywallOpenAccessProviderFactory(
            objectMapper,
            ProviderCallGate(changedCatalog),
            discoveryBuilder.build(),
            RestClient.builder().build(),
            "changed@example.invalid",
            1_000_000,
        ).forRun(configuredRun())

        assertThrows(ProviderCallRejectedException::class.java) {
            provider.discover(BibliographyReference("A legal open paper", listOf("A Researcher"), 2024, "10.1234/changed", "JOURNAL_ARTICLE"))
        }
        server.verify()
    }

    @Test
    fun `missing per-run consent sends no Unpaywall discovery request`() {
        val discoveryBuilder = RestClient.builder().baseUrl("https://api.unpaywall.org")
        val server = MockRestServiceServer.bindTo(discoveryBuilder).build()
        server.expect(ExpectedCount.never(), requestTo(containsString("api.unpaywall.org")))
        val configuration = configuredRun().copy(externalProviderConsents = emptyList())
        val provider = UnpaywallOpenAccessProviderFactory(
            objectMapper,
            ProviderCallGate(catalog),
            discoveryBuilder.build(),
            RestClient.builder().build(),
            contactEmail,
            1_000_000,
        ).forRun(configuration)

        assertThrows(ProviderCallRejectedException::class.java) {
            provider.discover(BibliographyReference("A legal open paper", listOf("A Researcher"), 2024, "10.1234/blocked", "JOURNAL_ARTICLE"))
        }

        server.verify()
    }

    @Test
    fun `missing per-run consent sends no content-host acquisition request`() {
        val contentBuilder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(contentBuilder).build()
        server.expect(ExpectedCount.never(), requestTo(containsString("8.8.8.8")))
        val configuration = configuredRun().copy(
            externalProviderConsents = listOf(
                ExternalProviderConsentSnapshot("unpaywall", listOf("bibliographic_metadata", "provider_contact_email")),
            ),
        )
        val provider = UnpaywallOpenAccessProviderFactory(
            objectMapper,
            ProviderCallGate(catalog),
            RestClient.builder().build(),
            contentBuilder.build(),
            contactEmail,
            1_000_000,
        ).forRun(configuration)

        assertThrows(ProviderCallRejectedException::class.java) {
            provider.fetch(OpenAccessLocation("https://8.8.8.8/paper.txt", "CC-BY", "publishedVersion", "repository", "unpaywall"))
        }

        server.verify()
    }

    @Test
    fun `consented discovery derives request data from DOI and configured contact email and fetches only approved full text`() {
        val discoveryBuilder = RestClient.builder().baseUrl("https://api.unpaywall.org")
        val contentBuilder = RestClient.builder()
        val discoveryServer = MockRestServiceServer.bindTo(discoveryBuilder).build()
        val contentServer = MockRestServiceServer.bindTo(contentBuilder).build()
        discoveryServer.expect(requestTo(containsString("api.unpaywall.org/v2/")))
            .andExpect(queryParam("email", contactEmail))
            .andRespond(withSuccess(
                """{"doi":"10.1234/open","abstract":"Summary","oa_locations":[{"url_for_pdf":"https://8.8.8.8/paper.txt","license":"cc-by","version":"publishedVersion","host_type":"repository"}]}""",
                MediaType.APPLICATION_JSON,
            ))
        contentServer.expect(requestTo("https://8.8.8.8/paper.txt"))
            .andRespond(withSuccess("English full text fixture", MediaType.TEXT_PLAIN))
        val provider = UnpaywallOpenAccessProviderFactory(
            objectMapper,
            ProviderCallGate(catalog),
            discoveryBuilder.build(),
            contentBuilder.build(),
            contactEmail,
            1_000_000,
        ).forRun(configuredRun())

        val discovery = provider.discover(BibliographyReference("A legal open paper", listOf("A Researcher"), 2024, "10.1234/open", "JOURNAL_ARTICLE"))!!
        val location = discovery.locations.single()
        val acquired = provider.fetch(location)

        assertEquals(true, discovery.abstractAvailable)
        assertEquals("https://8.8.8.8/paper.txt", location.url)
        assertEquals("English full text fixture", acquired.bytes.toString(Charsets.UTF_8))
        assertEquals("cc-by", acquired.location.license)
        discoveryServer.verify()
        contentServer.verify()
    }

    private fun configuredRun() = factory.from(
        RunConfigurationRequest(
            openAccessProvider = "unpaywall",
            externalProviderConsents = listOf(
                ExternalProviderConsentSnapshot("unpaywall", listOf("bibliographic_metadata", "cited_paper_location", "provider_contact_email")),
            ),
        ),
    )
}
