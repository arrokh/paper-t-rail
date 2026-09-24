package com.papertrail.api.runs

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.papertrail.api.providers.CLAIM_EXTRACTOR_ROLE
import com.papertrail.api.providers.DataCategory
import com.papertrail.api.providers.EMBEDDING_ROLE
import com.papertrail.api.providers.ProviderCallGate
import com.papertrail.api.providers.ProviderCallRejectedException
import com.papertrail.api.providers.ProviderCatalog
import com.papertrail.api.providers.ProviderRegistration
import com.papertrail.api.providers.ProviderTrustBoundary
import com.papertrail.api.providers.SYSTEM_ONE_ROLE
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RunConfigurationFactoryTest {
    private val factory = RunConfigurationFactory(
        objectMapper = jacksonObjectMapper(),
        providerCatalog = ProviderCatalog.safeDefaults(),
        parserVersion = "3.0.5",
        languageDetectorVersion = "0.6",
        limits = ValidationLimitsSnapshot(52_428_800, 500, 5_000_000, 100_000, 100, 0.65),
    )

    @Test
    fun `snapshots the selected safe local default configuration and parser limits`() {
        val request = factory.parseRequest(null)
        val snapshot = factory.from(request)

        assertEquals("heuristic", snapshot.claimExtractor.provider)
        assertEquals("LOCAL", snapshot.claimExtractor.trustBoundary)
        assertEquals(listOf("citation_context"), snapshot.claimExtractor.dataCategories)
        assertEquals("local", snapshot.embedding.provider)
        assertEquals(listOf("cited_paper_chunks", "embedding_input"), snapshot.embedding.dataCategories)
        assertEquals("mock", snapshot.systemOne.provider)
        assertEquals(listOf("atomic_claims", "evidence_passages"), snapshot.systemOne.dataCategories)
        assertEquals("pdfbox", snapshot.sourceParser.provider)
        assertEquals("3.0.5", snapshot.sourceParser.version)
        assertEquals(52_428_800, snapshot.validationLimits.maxUploadBytes)
        assertEquals(100_000, snapshot.validationLimits.maxExtractedCharactersPerPage)
        assertEquals(100, snapshot.validationLimits.minimumExtractedCharacters)
        assertEquals("NOT_RUN", snapshot.referenceResolution.executionStatus)
        assertEquals("NOT_RUN", snapshot.aggregation.executionStatus)
        assertEquals(0, snapshot.externalProviderConsents.size)
        val json = jacksonObjectMapper().readTree(factory.toJson(snapshot))
        assertTrue(json["referenceResolution"].has("confidenceThreshold"))
        assertTrue(json["referenceResolution"]["confidenceThreshold"].isNull)
        assertTrue(json["aggregation"].has("thresholds"))
        assertTrue(json["aggregation"]["thresholds"].isNull)
    }

    @Test
    fun `rejects external or disabled provider selections`() {
        assertThrows(IllegalArgumentException::class.java) {
            factory.from(RunConfigurationRequest(systemOneProvider = "external-vendor"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            factory.from(RunConfigurationRequest(embeddingProvider = "google-gemini-api"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            factoryFor().from(RunConfigurationRequest(systemOneProvider = "unreviewed-provider"))
        }
    }

    @Test
    fun `external providers cannot be enabled without an explicit reviewed retention disclosure`() {
        assertThrows(IllegalArgumentException::class.java) {
            ProviderCatalog(listOf(
                ProviderRegistration(
                    CLAIM_EXTRACTOR_ROLE,
                    "unreviewed-external",
                    "External provider",
                    "v1",
                    null,
                    ProviderTrustBoundary.EXTERNAL,
                    true,
                    setOf(DataCategory.CITATION_CONTEXT),
                ),
            ))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProviderCatalog(listOf(
                ProviderRegistration(
                    SYSTEM_ONE_ROLE,
                    "unclassified",
                    "Unclassified provider",
                    "v1",
                    null,
                    ProviderTrustBoundary.UNREVIEWED,
                    true,
                    setOf(DataCategory.ATOMIC_CLAIMS),
                ),
            ))
        }
    }

    @Test
    fun `requires exact per-run provider consent for selected external payload categories`() {
        val externalFactory = factoryFor()
        val selected = RunConfigurationRequest(claimExtractorProvider = "reviewed-llm")
        assertThrows(IllegalArgumentException::class.java) { externalFactory.from(selected) }

        val approved = selected.copy(
            externalProviderConsents = listOf(
                ExternalProviderConsentSnapshot("reviewed-llm", listOf("citation_context")),
            ),
        )
        val snapshot = externalFactory.from(approved)
        assertEquals("EXTERNAL", snapshot.claimExtractor.trustBoundary)
        assertEquals(listOf("citation_context"), snapshot.externalProviderConsents.single().dataCategories)

        assertThrows(IllegalArgumentException::class.java) {
            externalFactory.from(approved.copy(externalProviderConsents = listOf(
                ExternalProviderConsentSnapshot("reviewed-llm", listOf("atomic_claims")),
            )))
        }
        assertThrows(IllegalArgumentException::class.java) {
            externalFactory.from(approved.copy(externalProviderConsents = listOf(
                ExternalProviderConsentSnapshot("reviewed-llm", listOf("citation_context", "citation_context")),
            )))
        }
        assertThrows(IllegalArgumentException::class.java) {
            externalFactory.from(approved.copy(externalProviderConsents = listOf(
                ExternalProviderConsentSnapshot("mock", listOf("citation_context")),
                ExternalProviderConsentSnapshot("reviewed-llm", listOf("citation_context")),
            )))
        }
    }

    @Test
    fun `parses provider-specific consent categories and rejects unknown category identifiers`() {
        val externalFactory = factoryFor()
        val parsed = externalFactory.parseRequest(
            jacksonObjectMapper().readTree(
                """{"claimExtractorProvider":"reviewed-llm","externalProviderConsents":[{"providerId":"reviewed-llm","dataCategories":["citation_context"]}]}""",
            ),
        )
        assertEquals(listOf("citation_context"), parsed.externalProviderConsents.single().dataCategories)
        assertEquals("reviewed-llm", externalFactory.from(parsed).claimExtractor.provider)
        val unknownCategory = externalFactory.parseRequest(
            jacksonObjectMapper().readTree(
                """{"claimExtractorProvider":"reviewed-llm","externalProviderConsents":[{"providerId":"reviewed-llm","dataCategories":["made_up_category"]}]}""",
            ),
        )
        assertThrows(IllegalArgumentException::class.java) { externalFactory.from(unknownCategory) }
    }

    @Test
    fun `provider call gate checks actual request categories before invoking the outbound action`() {
        val externalFactory = factoryFor()
        val snapshot = externalFactory.from(
            RunConfigurationRequest(
                claimExtractorProvider = "reviewed-llm",
                externalProviderConsents = listOf(
                    ExternalProviderConsentSnapshot("reviewed-llm", listOf("citation_context")),
                ),
            ),
        )
        val gate = ProviderCallGate(externalCatalog())
        var outboundCallStarted = false

        assertThrows(ProviderCallRejectedException::class.java) {
            gate.call(
                CLAIM_EXTRACTOR_ROLE,
                "reviewed-llm",
                setOf(DataCategory.CITATION_CONTEXT, DataCategory.SOURCE_DOCUMENT_TEXT),
                snapshot,
            ) { outboundCallStarted = true }
        }
        assertFalse(outboundCallStarted)
        assertThrows(ProviderCallRejectedException::class.java) {
            gate.call(
                CLAIM_EXTRACTOR_ROLE,
                "reviewed-llm",
                setOf(DataCategory.CITATION_CONTEXT),
                snapshot.copy(externalProviderConsents = emptyList()),
            ) { outboundCallStarted = true }
        }
        assertThrows(ProviderCallRejectedException::class.java) {
            gate.call(
                CLAIM_EXTRACTOR_ROLE,
                "reviewed-llm",
                setOf(DataCategory.CITATION_CONTEXT),
                snapshot.copy(claimExtractor = snapshot.claimExtractor.copy(dataCategories = listOf("source_document_text"))),
            ) { outboundCallStarted = true }
        }
        assertFalse(outboundCallStarted)

        assertEquals("response", gate.call(
            CLAIM_EXTRACTOR_ROLE,
            "reviewed-llm",
            setOf(DataCategory.CITATION_CONTEXT),
            snapshot,
        ) { outboundCallStarted = true; "response" })
        assertTrue(outboundCallStarted)
    }

    @Test
    fun `disabled and unclassified providers are rejected before an outbound action`() {
        val snapshot = factory.from(RunConfigurationRequest())
        val disabledGate = ProviderCallGate(ProviderCatalog.safeDefaults())
        val unreviewedGate = ProviderCallGate(externalCatalog())
        var outboundCallStarted = false

        assertThrows(ProviderCallRejectedException::class.java) {
            disabledGate.call(
                CLAIM_EXTRACTOR_ROLE,
                "google-gemini-api",
                setOf(DataCategory.CITATION_CONTEXT),
                snapshot,
            ) { outboundCallStarted = true }
        }
        assertThrows(ProviderCallRejectedException::class.java) {
            unreviewedGate.call(
                SYSTEM_ONE_ROLE,
                "unreviewed-provider",
                setOf(DataCategory.ATOMIC_CLAIMS),
                snapshot,
            ) { outboundCallStarted = true }
        }
        assertFalse(outboundCallStarted)
    }

    private fun factoryFor(): RunConfigurationFactory = RunConfigurationFactory(
        objectMapper = jacksonObjectMapper(),
        providerCatalog = externalCatalog(),
        parserVersion = "3.0.5",
        languageDetectorVersion = "0.6",
        limits = ValidationLimitsSnapshot(52_428_800, 500, 5_000_000, 100_000, 100, 0.65),
    )

    private fun externalCatalog(): ProviderCatalog = ProviderCatalog(
        buildList {
            add(ProviderRegistration(CLAIM_EXTRACTOR_ROLE, "heuristic", "Heuristic", "v1", null, ProviderTrustBoundary.LOCAL, true, setOf(DataCategory.CITATION_CONTEXT)))
            add(ProviderRegistration(
                CLAIM_EXTRACTOR_ROLE,
                "reviewed-llm",
                "Reviewed LLM",
                "v1",
                "model-1",
                ProviderTrustBoundary.EXTERNAL,
                true,
                setOf(DataCategory.CITATION_CONTEXT),
                retentionDisclosure = "Reviewed retention and deletion terms for this test deployment.",
                enablementReviewed = true,
            ))
            add(ProviderRegistration(EMBEDDING_ROLE, "local", "Local embeddings", "v1", "e5-small-v2", ProviderTrustBoundary.LOCAL, true, setOf(DataCategory.CITED_PAPER_CHUNKS, DataCategory.EMBEDDING_INPUT)))
            add(ProviderRegistration(SYSTEM_ONE_ROLE, "mock", "Mock", "v1", "mock-v1", ProviderTrustBoundary.LOCAL, true, setOf(DataCategory.ATOMIC_CLAIMS, DataCategory.EVIDENCE_PASSAGES)))
            add(ProviderRegistration(SYSTEM_ONE_ROLE, "unreviewed-provider", "Unreviewed provider fixture", "unknown", null, ProviderTrustBoundary.UNREVIEWED, false, setOf(DataCategory.ATOMIC_CLAIMS, DataCategory.EVIDENCE_PASSAGES)))
        },
    )

    @Test
    fun `rejects unknown configuration fields instead of silently ignoring them`() {
        val configuration = jacksonObjectMapper().readTree("""{"claimExtractorProvider":"heuristic","unexpected":true}""")
        assertThrows(IllegalArgumentException::class.java) { factory.parseRequest(configuration) }
    }
}
