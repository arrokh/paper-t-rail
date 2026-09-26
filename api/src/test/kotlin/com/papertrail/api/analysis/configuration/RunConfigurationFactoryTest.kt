package com.papertrail.api.analysis.configuration

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.papertrail.api.analysis.http.RunConfigurationRequest
import com.papertrail.api.infrastructure.providers.CLAIM_EXTRACTOR_ROLE
import com.papertrail.api.infrastructure.providers.DataCategory
import com.papertrail.api.infrastructure.providers.ProviderCallGate
import com.papertrail.api.infrastructure.providers.ProviderCallPayload
import com.papertrail.api.infrastructure.providers.ProviderCallRejectedException
import com.papertrail.api.infrastructure.providers.ProviderCatalog
import com.papertrail.api.infrastructure.providers.ProviderRegistration
import com.papertrail.api.infrastructure.providers.ProviderTrustBoundary
import com.papertrail.api.infrastructure.providers.SYSTEM_ONE_ROLE
import com.papertrail.api.infrastructure.providers.reviewedExternalProviderCatalog
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RunConfigurationFactoryTest {
    private val factory = RunConfigurationFactory(
        objectMapper = jacksonObjectMapper(),
        providerCatalog = ProviderCatalog.safeDefaults(),
        parserId = "grobid",
        parserVersion = "0.9.1-crf",
        languageDetectorVersion = "0.6",
        limits = ValidationLimitsSnapshot(52_428_800, 500, 5_000_000, 100_000, 100, 0.65),
    )

    @Test
    fun `snapshots the selected safe local default configuration and parser limits`() {
        val request = factory.parseRequest(null)
        val snapshot = factory.from(request)

        assertEquals("heuristic", snapshot.claimExtractor.provider)
        assertEquals("v1", snapshot.claimExtractor.version)
        assertEquals("LOCAL", snapshot.claimExtractor.trustBoundary)
        assertEquals(listOf("citation_context"), snapshot.claimExtractor.dataCategories)
        assertEquals("local", snapshot.embedding.provider)
        assertEquals("feature-hash-384-v1", snapshot.embedding.model)
        assertEquals(listOf("cited_paper_chunks", "embedding_input"), snapshot.embedding.dataCategories)
        assertEquals("postgres-hybrid-rrf-v1", snapshot.retrieval.profileId)
        assertEquals(10, snapshot.retrieval.vectorCandidateLimit)
        assertEquals(10, snapshot.retrieval.lexicalCandidateLimit)
        assertEquals(5, snapshot.retrieval.finalCandidateLimit)
        assertEquals(60, snapshot.retrieval.reciprocalRankFusionConstant)
        assertTrue(snapshot.retrieval.embeddingProfileHash.matches(Regex("[0-9a-f]{64}")))
        assertEquals("mock", snapshot.systemOne.provider)
        assertEquals(listOf("atomic_claims", "evidence_passages"), snapshot.systemOne.dataCategories)
        assertEquals("grobid", snapshot.sourceParser.provider)
        assertEquals("0.9.1-crf", snapshot.sourceParser.version)
        assertEquals(52_428_800, snapshot.validationLimits.maxUploadBytes)
        assertEquals(100_000, snapshot.validationLimits.maxExtractedCharactersPerPage)
        assertEquals(100, snapshot.validationLimits.minimumExtractedCharacters)
        assertEquals("PENDING", snapshot.referenceResolution.executionStatus)
        val metadataProvider = snapshot.referenceResolution.provider!!
        assertEquals("recorded-fixtures", metadataProvider.provider)
        assertEquals("LOCAL", metadataProvider.trustBoundary)
        assertEquals(listOf("bibliographic_metadata"), metadataProvider.dataCategories)
        assertEquals("title-author-year-weighted-edit-similarity-v1", snapshot.referenceResolution.scorePolicyVersion)
        assertEquals(0.9, snapshot.referenceResolution.confidenceThreshold)
        assertEquals("NOT_RUN", snapshot.aggregation.executionStatus)
        assertEquals(0, snapshot.externalProviderConsents.size)
        val json = jacksonObjectMapper().readTree(factory.toJson(snapshot))
        assertTrue(json["referenceResolution"].has("confidenceThreshold"))
        assertEquals(0.9, json["referenceResolution"]["confidenceThreshold"].asDouble())
        assertTrue(json["aggregation"].has("thresholds"))
        assertTrue(json["aggregation"]["thresholds"].isNull)
    }

    @Test
    fun `pins deployment-configured retrieval parameters into the immutable run snapshot`() {
        val configured = factoryFor(
            retrievalProfileId = "postgres-hybrid-rrf-v2",
            vectorCandidateLimit = 12,
            lexicalCandidateLimit = 8,
            finalCandidateLimit = 4,
            reciprocalRankFusionConstant = 30,
        ).from(RunConfigurationRequest())

        assertEquals("postgres-hybrid-rrf-v2", configured.retrieval.profileId)
        assertEquals(12, configured.retrieval.vectorCandidateLimit)
        assertEquals(8, configured.retrieval.lexicalCandidateLimit)
        assertEquals(4, configured.retrieval.finalCandidateLimit)
        assertEquals(30, configured.retrieval.reciprocalRankFusionConstant)
    }

    @Test
    fun `loads immutable pre-resolution run snapshots without inventing a policy`() {
        val objectMapper = jacksonObjectMapper()
        val legacyTree = objectMapper.readTree(factory.toJson(factory.from(RunConfigurationRequest()))) as ObjectNode
        legacyTree.remove("referenceResolution")

        val legacySnapshot = objectMapper.treeToValue(legacyTree, AnalysisConfigurationSnapshot::class.java)

        assertEquals("NOT_RUN", legacySnapshot.referenceResolution.executionStatus)
        assertEquals(null, legacySnapshot.referenceResolution.provider)
        assertEquals(null, legacySnapshot.referenceResolution.scorePolicyVersion)
        assertEquals(null, legacySnapshot.referenceResolution.confidenceThreshold)
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
            factory.from(RunConfigurationRequest(scholarlyMetadataProvider = "crossref"))
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
    fun `Crossref selection must be explicitly consented in the Analysis Run snapshot`() {
        val externalFactory = factoryFor()
        assertThrows(IllegalArgumentException::class.java) {
            externalFactory.from(RunConfigurationRequest(scholarlyMetadataProvider = "crossref"))
        }

        val snapshot = externalFactory.from(
            RunConfigurationRequest(
                scholarlyMetadataProvider = "crossref",
                externalProviderConsents = listOf(
                    ExternalProviderConsentSnapshot("crossref", listOf("bibliographic_metadata")),
                ),
            ),
        )
        val metadataProvider = snapshot.referenceResolution.provider!!
        assertEquals("crossref", metadataProvider.provider)
        assertEquals("EXTERNAL", metadataProvider.trustBoundary)
        assertEquals("crossref", snapshot.externalProviderConsents.single().providerId)
        assertEquals(listOf("bibliographic_metadata"), snapshot.externalProviderConsents.single().dataCategories)
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
        val gate = ProviderCallGate(reviewedExternalProviderCatalog())
        var outboundCallStarted = false

        assertThrows(ProviderCallRejectedException::class.java) {
            gate.call(
                CLAIM_EXTRACTOR_ROLE,
                "reviewed-llm",
                providerCallPayload(DataCategory.CITATION_CONTEXT, DataCategory.SOURCE_DOCUMENT_TEXT),
                snapshot,
            ) { _ -> outboundCallStarted = true }
        }
        assertFalse(outboundCallStarted)
        assertThrows(ProviderCallRejectedException::class.java) {
            gate.call(
                CLAIM_EXTRACTOR_ROLE,
                "reviewed-llm",
                providerCallPayload(DataCategory.CITATION_CONTEXT),
                snapshot.copy(externalProviderConsents = emptyList()),
            ) { _ -> outboundCallStarted = true }
        }
        assertThrows(ProviderCallRejectedException::class.java) {
            gate.call(
                CLAIM_EXTRACTOR_ROLE,
                "reviewed-llm",
                providerCallPayload(DataCategory.CITATION_CONTEXT),
                snapshot.copy(claimExtractor = snapshot.claimExtractor.copy(dataCategories = listOf("source_document_text"))),
            ) { _ -> outboundCallStarted = true }
        }
        assertThrows(ProviderCallRejectedException::class.java) {
            gate.call(
                CLAIM_EXTRACTOR_ROLE,
                "reviewed-llm",
                ProviderCallPayload(emptyMap()),
                snapshot,
            ) { _ -> outboundCallStarted = true }
        }
        assertFalse(outboundCallStarted)

        assertEquals("response", gate.call(
            CLAIM_EXTRACTOR_ROLE,
            "reviewed-llm",
            providerCallPayload(DataCategory.CITATION_CONTEXT),
            snapshot,
        ) { payload ->
            outboundCallStarted = true
            assertEquals(setOf(DataCategory.CITATION_CONTEXT), payload.dataCategories)
            assertEquals("citation_context content", payload.contentByCategory.getValue(DataCategory.CITATION_CONTEXT).asText())
            "response"
        })
        assertTrue(outboundCallStarted)
    }

    @Test
    fun `disabled and unclassified providers are rejected before an outbound action`() {
        val snapshot = factory.from(RunConfigurationRequest())
        val disabledGate = ProviderCallGate(ProviderCatalog.safeDefaults())
        val unreviewedGate = ProviderCallGate(reviewedExternalProviderCatalog())
        var outboundCallStarted = false

        assertThrows(ProviderCallRejectedException::class.java) {
            disabledGate.call(
                CLAIM_EXTRACTOR_ROLE,
                "google-gemini-api",
                providerCallPayload(DataCategory.CITATION_CONTEXT),
                snapshot,
            ) { _ -> outboundCallStarted = true }
        }
        assertThrows(ProviderCallRejectedException::class.java) {
            unreviewedGate.call(
                SYSTEM_ONE_ROLE,
                "unreviewed-provider",
                providerCallPayload(DataCategory.ATOMIC_CLAIMS),
                snapshot,
            ) { _ -> outboundCallStarted = true }
        }
        assertFalse(outboundCallStarted)
    }

    private fun providerCallPayload(vararg categories: DataCategory): ProviderCallPayload = ProviderCallPayload(
        categories.associateWith { jacksonObjectMapper().readTree("\"${it.id} content\"") },
    )

    private fun factoryFor(
        retrievalProfileId: String = "postgres-hybrid-rrf-v1",
        vectorCandidateLimit: Int = 10,
        lexicalCandidateLimit: Int = 10,
        finalCandidateLimit: Int = 5,
        reciprocalRankFusionConstant: Int = 60,
    ): RunConfigurationFactory = RunConfigurationFactory(
        objectMapper = jacksonObjectMapper(),
        providerCatalog = reviewedExternalProviderCatalog(),
        parserId = "grobid",
        parserVersion = "0.9.1-crf",
        languageDetectorVersion = "0.6",
        limits = ValidationLimitsSnapshot(52_428_800, 500, 5_000_000, 100_000, 100, 0.65),
        retrievalProfileId = retrievalProfileId,
        vectorCandidateLimit = vectorCandidateLimit,
        lexicalCandidateLimit = lexicalCandidateLimit,
        finalCandidateLimit = finalCandidateLimit,
        reciprocalRankFusionConstant = reciprocalRankFusionConstant,
    )

    @Test
    fun `rejects unknown configuration fields instead of silently ignoring them`() {
        val configuration = jacksonObjectMapper().readTree("""{"claimExtractorProvider":"heuristic","unexpected":true}""")
        assertThrows(IllegalArgumentException::class.java) { factory.parseRequest(configuration) }
    }
}
