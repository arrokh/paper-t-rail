package com.papertrail.api.scholarly.acquisition.client

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.papertrail.api.analysis.configuration.RunConfigurationFactory
import com.papertrail.api.analysis.configuration.ValidationLimitsSnapshot
import com.papertrail.api.analysis.execution.repository.AnalysisRunExecutionRepository
import com.papertrail.api.analysis.execution.service.AnalysisRunExecutionService
import com.papertrail.api.analysis.execution.domain.CaptureFidelity
import com.papertrail.api.analysis.execution.service.ExecutionCaptureSanitizer
import com.papertrail.api.analysis.execution.domain.ExecutionOperationId
import com.papertrail.api.analysis.execution.domain.ExecutionSpanArtifactSpec
import com.papertrail.api.analysis.execution.domain.ExecutionSpanHandle
import com.papertrail.api.analysis.execution.domain.ExecutionSpanSpec
import com.papertrail.api.analysis.execution.domain.SanitizedExecutionArtifact
import com.papertrail.api.analysis.http.RunConfigurationRequest
import com.papertrail.api.infrastructure.providers.ProviderCallGate
import com.papertrail.api.infrastructure.providers.ProviderCallRejectedException
import com.papertrail.api.infrastructure.providers.ProviderCatalog
import com.papertrail.api.infrastructure.providers.externalProviderConsent
import com.papertrail.api.scholarly.acquisition.domain.OpenAccessLocation
import com.papertrail.api.scholarly.references.client.BibliographyReference
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.http.MediaType
import org.springframework.test.web.client.ExpectedCount
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import org.hamcrest.Matchers.containsString
import java.time.Instant
import java.util.UUID
import com.papertrail.api.external.unpaywall.UnpaywallOpenAccessProviderFactory

class UnpaywallOpenAccessProviderFactoryTest {
    private val objectMapper = jacksonObjectMapper()
    private val contactEmail = "researcher@example.invalid"
    private val catalog = ProviderCatalog.safeDefaults(
        unpaywallEnabled = true,
        unpaywallRetentionDisclosure = null,
        unpaywallContactEmail = contactEmail,
    )
    private val factory = RunConfigurationFactory(
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
            "Paper T-Rail has not verified this provider's data-retention or deletion terms. Retention and deletion details are unknown; consult the provider's terms.",
            configuration.openAccessRetentionDisclosure,
        )
    }

    @Test
    fun `changed Unpaywall contact settings reject requests pinned to an earlier run`() {
        val changedCatalog = ProviderCatalog.safeDefaults(
            unpaywallEnabled = true,
            unpaywallRetentionDisclosure = null,
            unpaywallContactEmail = "changed@example.invalid",
        )
        val discoveryBuilder = RestClient.builder().baseUrl("https://api.unpaywall.org")
        val server = MockRestServiceServer.bindTo(discoveryBuilder).build()
        server.expect(ExpectedCount.never(), requestTo(containsString("api.unpaywall.org")))
        val provider = UnpaywallOpenAccessProviderFactory(
            ProviderCallGate(changedCatalog),
            NoOpUnpaywallDiscoveryCache(),
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
            ProviderCallGate(catalog),
            NoOpUnpaywallDiscoveryCache(),
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
        val originalConsent = configuredRun().externalProviderConsents.single()
        val configuration = configuredRun().copy(
            externalProviderConsents = listOf(
                originalConsent.copy(dataCategories = listOf("bibliographic_metadata", "provider_contact_email")),
            ),
        )
        val provider = UnpaywallOpenAccessProviderFactory(
            ProviderCallGate(catalog),
            NoOpUnpaywallDiscoveryCache(),
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
                """{"doi":"10.1234/open","abstract":"Public summary","emailAddress":"private@example.org","apiKey":"sk-private-value","oa_locations":[{"url_for_pdf":"https://8.8.8.8/paper.txt","license":"cc-by","version":"publishedVersion","host_type":"repository"}]}""",
                MediaType.APPLICATION_JSON,
            ))
        contentServer.expect(requestTo("https://8.8.8.8/paper.txt"))
            .andRespond(withSuccess("English full text fixture", MediaType.TEXT_PLAIN))
        val repository = Mockito.mock(AnalysisRunExecutionRepository::class.java)
        val runId = UUID.randomUUID()
        val eventId = UUID.randomUUID()
        val parentSpec = ExecutionSpanSpec("access", "INTERNAL", "Acquire test paper", eventId = eventId)
        val parentHandle = ExecutionSpanHandle(UUID.randomUUID(), runId, UUID.randomUUID(), Instant.now(), System.nanoTime(), "access", 1, eventId)
        Mockito.`when`(repository.startSpan(runId, parentSpec, "{}")).thenReturn(parentHandle)
        val childSpecs = listOf(
            ExecutionSpanSpec(
                "access", "PROVIDER", "Unpaywall discovery request", eventId = eventId,
                parentSpanId = parentHandle.id, providerId = "unpaywall",
                operationId = ExecutionOperationId.forEvent(eventId, "provider-call:unpaywall-discovery"),
                attributes = mapOf("httpRoute" to "/v2"),
            ),
            ExecutionSpanSpec(
                "access", "PROVIDER", "Acquire open-access full text", eventId = eventId,
                parentSpanId = parentHandle.id, providerId = "unpaywall",
                operationId = ExecutionOperationId.forEvent(eventId, "provider-call:unpaywall-full-text"),
                attributes = mapOf("httpRoute" to "/open-access-content"),
            ),
        )
        childSpecs.forEach { spec ->
            val route = spec.attributes.getValue("httpRoute")
            Mockito.`when`(repository.startSpan(runId, spec, """{"httpRoute":"$route"}"""))
                .thenReturn(ExecutionSpanHandle(UUID.randomUUID(), runId, spec.operationId!!, Instant.now(), System.nanoTime(), "access", 1, eventId))
        }
        val executionService = AnalysisRunExecutionService(repository, ExecutionCaptureSanitizer())
        val provider = UnpaywallOpenAccessProviderFactory(
            ProviderCallGate(catalog),
            NoOpUnpaywallDiscoveryCache(),
            discoveryBuilder.build(),
            contentBuilder.build(),
            contactEmail,
            1_000_000,
            executionService,
        ).forRun(configuredRun())

        val result = executionService.record(runId, parentSpec) {
            val discovery = provider.discover(BibliographyReference("A legal open paper", listOf("A Researcher"), 2024, "10.1234/open", "JOURNAL_ARTICLE"))!!
            val location = discovery.locations.single()
            discovery to provider.fetch(location)
        }
        val discovery = result.first
        val acquired = result.second
        val capturedArtifacts = Mockito.mockingDetails(repository).invocations
            .filter { it.method.name == "recordArtifact" }
            .map { it.arguments[2] as ExecutionSpanArtifactSpec to it.arguments[3] as SanitizedExecutionArtifact }
        val unpaywallRequest = capturedArtifacts[0].second.content!!
        val unpaywallResponse = capturedArtifacts[1].second.content!!
        val contentRequest = capturedArtifacts[2].second.content!!
        val contentMetadata = capturedArtifacts[4].second.content!!

        assertEquals(true, discovery.abstractAvailable)
        assertEquals("https://8.8.8.8/paper.txt", discovery.locations.single().url)
        assertEquals("English full text fixture", acquired.bytes.toString(Charsets.UTF_8))
        assertEquals("cc-by", acquired.location.license)
        assertEquals(listOf("REQUEST", "RESPONSE", "REQUEST", "RESPONSE", "RESULT"), capturedArtifacts.map { it.first.role })
        assertEquals(CaptureFidelity.SANITIZED, capturedArtifacts[0].second.fidelity)
        assertTrue(unpaywallRequest.contains("10.1234/open"))
        assertTrue(unpaywallRequest.contains("contactParameterOmitted"))
        assertFalse(unpaywallRequest.contains(contactEmail))
        assertEquals(CaptureFidelity.PARTIAL, capturedArtifacts[1].second.fidelity)
        assertTrue(unpaywallResponse.contains("\"abstractAvailable\":true"))
        assertFalse(unpaywallResponse.contains("Public summary"))
        assertFalse(unpaywallResponse.contains("private@example.org"))
        assertFalse(unpaywallResponse.contains("sk-private-value"))
        assertTrue(unpaywallResponse.contains("https://8.8.8.8/paper.txt"))
        assertFalse(unpaywallResponse.contains("private-value"))
        assertTrue(contentRequest.contains("https://8.8.8.8/paper.txt"))
        assertFalse(contentRequest.contains("private-value"))
        assertEquals(CaptureFidelity.OMITTED, capturedArtifacts[3].second.fidelity)
        assertEquals("BINARY_ASSET_REFERENCE", capturedArtifacts[3].second.reason)
        assertEquals(null, capturedArtifacts[3].second.content)
        assertFalse(contentMetadata.contains("application/pdf"))
        assertTrue(contentMetadata.contains("text/plain"))
        assertTrue(contentMetadata.contains("200"))
        assertFalse(contentMetadata.contains("English full text fixture"))
        discoveryServer.verify()
        contentServer.verify()
    }

    private fun configuredRun() = factory.from(
        RunConfigurationRequest(
            openAccessProvider = "unpaywall",
            externalProviderConsents = listOf(
                externalProviderConsent(catalog, "unpaywall", listOf("bibliographic_metadata", "cited_paper_location", "provider_contact_email")),
            ),
        ),
    )
}
