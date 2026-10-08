package com.papertrail.api.analysis.configuration

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.papertrail.api.analysis.http.RunConfigurationRequest
import com.papertrail.api.citation.parsing.BibliographyNormalizationPolicySelection
import com.papertrail.api.evidence.verification.domain.EvidenceAggregationThresholds
import com.papertrail.api.evidence.verification.domain.TestEvidenceAggregationThresholds
import com.papertrail.api.external.jev.JevSystemOneSettings
import com.papertrail.api.external.laya.LayaSystemOneSettings
import com.papertrail.api.external.ollama.OllamaEmbeddingSettings
import com.papertrail.api.citation.claims.provider.OpenAiCompatibleClaimAnalysisSettings
import com.papertrail.api.infrastructure.providers.CLAIM_EXTRACTOR_ROLE
import com.papertrail.api.infrastructure.providers.DataCategory
import com.papertrail.api.infrastructure.providers.ProviderCallGate
import com.papertrail.api.infrastructure.providers.ProviderCallPayload
import com.papertrail.api.infrastructure.providers.ProviderCallRejectedException
import com.papertrail.api.infrastructure.providers.ProviderCatalog
import com.papertrail.api.infrastructure.providers.ProviderNotSelectableException
import com.papertrail.api.infrastructure.providers.ProviderRegistration
import com.papertrail.api.infrastructure.providers.ProviderTrustBoundary
import com.papertrail.api.infrastructure.providers.SYSTEM_ONE_ROLE
import com.papertrail.api.infrastructure.providers.externalProviderConsent
import com.papertrail.api.infrastructure.providers.configuredExternalProviderCatalog
import com.papertrail.api.external.openai.OpenAiCompatibleEndpointSettings
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RunConfigurationFactoryTest {
    private val factory = RunConfigurationFactory(
        providerCatalog = ProviderCatalog.safeDefaults(),
        parserId = "grobid",
        parserVersion = "0.9.1-crf",
        languageDetectorVersion = "0.6",
        limits = ValidationLimitsSnapshot(52_428_800, 500, 5_000_000, 100_000, 100, 0.65),
    )

    @Test
    fun `execution capture defaults on and explicit opt-out is preserved in the immutable run snapshot`() {
        val defaultSnapshot = factory.from(factory.parseRequest(jacksonObjectMapper().readTree("{}")))
        val optedOutSnapshot = factory.from(factory.parseRequest(jacksonObjectMapper().readTree("""{"captureExecution":false}""")))

        assertTrue(defaultSnapshot.captureExecution)
        assertFalse(optedOutSnapshot.captureExecution)
        assertThrows(IllegalArgumentException::class.java) {
            factory.parseRequest(jacksonObjectMapper().readTree("""{"captureExecution":"false"}"""))
        }
    }

    @Test
    fun `snapshots the selected safe local default configuration and parser limits`() {
        val request = factory.parseRequest(null)
        val snapshot = factory.from(request)

        assertEquals("heuristic", snapshot.claimExtractor.provider)
        assertEquals("v1", snapshot.claimExtractor.version)
        assertEquals("LOCAL", snapshot.claimExtractor.trustBoundary)
        assertEquals(listOf("citation_context"), snapshot.claimExtractor.dataCategories)
        assertEquals("heuristic-all-context-targets-v1", snapshot.claimExtractor.targetSelectionPolicyVersion)
        assertEquals("heuristic-claim-analysis-v1", snapshot.claimExtractor.outputMappingVersion)
        assertEquals(null, snapshot.claimExtractor.promptVersion)
        assertEquals("local", snapshot.embedding.provider)
        assertEquals("feature-hash-384-v1", snapshot.embedding.model)
        assertEquals(listOf("atomic_claims", "cited_paper_chunks", "embedding_input"), snapshot.embedding.dataCategories)
        assertEquals("postgres-hybrid-rrf-v1", snapshot.retrieval.profileId)
        assertEquals(3, snapshot.retrieval.vectorCandidateLimit)
        assertEquals(3, snapshot.retrieval.lexicalCandidateLimit)
        assertEquals(3, snapshot.retrieval.finalCandidateLimit)
        assertEquals(60, snapshot.retrieval.reciprocalRankFusionConstant)
        assertTrue(snapshot.retrieval.embeddingProfileHash.matches(Regex("[0-9a-f]{64}")))
        assertEquals("mock", snapshot.systemOne.provider)
        assertEquals(listOf("atomic_claims", "evidence_passages"), snapshot.systemOne.dataCategories)
        assertEquals("grobid", snapshot.sourceParser.provider)
        assertEquals("0.9.1-crf", snapshot.sourceParser.version)
        assertEquals("docling", snapshot.citedPaperParser?.provider)
        assertEquals("1.30.0", snapshot.citedPaperParser?.version)
        assertEquals(BibliographyNormalizationPolicySelection.CURRENT, snapshot.bibliographyNormalizationPolicy)
        assertEquals(52_428_800, snapshot.validationLimits.maxUploadBytes)
        assertEquals(100_000, snapshot.validationLimits.maxExtractedCharactersPerPage)
        assertEquals(5_000, snapshot.validationLimits.maxClaimCitationPairs)
        assertEquals(100, snapshot.validationLimits.minimumExtractedCharacters)
        assertEquals("PENDING", snapshot.referenceResolution.executionStatus)
        val metadataProvider = snapshot.referenceResolution.provider!!
        assertEquals("recorded-fixtures", metadataProvider.provider)
        assertEquals("LOCAL", metadataProvider.trustBoundary)
        assertEquals(listOf("bibliographic_metadata"), metadataProvider.dataCategories)
        assertEquals("title-author-year-weighted-edit-similarity-v1", snapshot.referenceResolution.scorePolicyVersion)
        assertEquals(0.25, snapshot.referenceResolution.confidenceThreshold)
        assertEquals("NOT_RUN", snapshot.aggregation.executionStatus)
        assertEquals(null, snapshot.aggregation.verificationPolicyVersion)
        assertEquals(null, snapshot.aggregation.aggregationPolicyVersion)
        assertEquals(null, snapshot.aggregation.thresholds)
        assertEquals(0, snapshot.externalProviderConsents.size)
        val json = jacksonObjectMapper().readTree(factory.toJson(snapshot))
        assertTrue(json["referenceResolution"].has("confidenceThreshold"))
        assertEquals(0.25, json["referenceResolution"]["confidenceThreshold"].asDouble())
        assertTrue(json["aggregation"]["thresholds"].isNull)
    }

    @Test
    fun `pins explicit OpenAI-compatible claim-analysis provenance without storing endpoint or credentials`() {
        val settings = OpenAiCompatibleClaimAnalysisSettings(
            endpoint = OpenAiCompatibleEndpointSettings(
                enabled = true,
                baseUrl = "http://127.0.0.1:9090/v1",
                apiKey = "server-side-secret",
                trustedHosts = setOf("127.0.0.1"),
            ),
            modelId = "fixture-model",
        )
        val catalog = ProviderCatalog.safeDefaults(openAiCompatibleClaimAnalysisSettings = settings)
        val configuredFactory = factoryFor(providerCatalog = catalog)

        val selected = configuredFactory.from(
            RunConfigurationRequest(claimExtractorProvider = OpenAiCompatibleClaimAnalysisSettings.PROVIDER_ID),
        ).claimExtractor
        val default = configuredFactory.from(RunConfigurationRequest()).claimExtractor
        val serialized = jacksonObjectMapper().writeValueAsString(selected)

        assertEquals(OpenAiCompatibleClaimAnalysisSettings.PROVIDER_ID, selected.provider)
        assertEquals("fixture-model", selected.model)
        assertEquals("LOCAL", selected.trustBoundary)
        assertEquals("model-selected-schema-constrained-same-context-targets-v2", selected.targetSelectionPolicyVersion)
        assertEquals("document-claim-analysis-single-context-v4", selected.promptVersion)
        assertEquals("chat-completions-claim-analysis-claims-array-v3", selected.outputMappingVersion)
        assertTrue(selected.configurationFingerprint!!.matches(Regex("[0-9a-f]{64}")))
        assertEquals("heuristic", default.provider)
        assertFalse(serialized.contains("server-side-secret"))
        assertFalse(serialized.contains("127.0.0.1"))
    }

    @Test
    fun `uses the configured default claim analyzer when omitted and keeps heuristic explicitly selectable`() {
        val settings = OpenAiCompatibleClaimAnalysisSettings(
            endpoint = OpenAiCompatibleEndpointSettings(
                enabled = true,
                baseUrl = "http://127.0.0.1:1234",
                trustedHosts = setOf("127.0.0.1"),
            ),
            modelId = "google/gemma-4-e2b",
            contextWindowTokens = 131_072,
        )
        val catalog = ProviderCatalog.safeDefaults(openAiCompatibleClaimAnalysisSettings = settings)
        val configuredFactory = factoryFor(
            providerCatalog = catalog,
            defaultClaimExtractorProvider = OpenAiCompatibleClaimAnalysisSettings.PROVIDER_ID,
        )

        val default = configuredFactory.from(configuredFactory.parseRequest(null)).claimExtractor
        val heuristic = configuredFactory.from(RunConfigurationRequest(claimExtractorProvider = "heuristic")).claimExtractor

        assertEquals(OpenAiCompatibleClaimAnalysisSettings.PROVIDER_ID, default.provider)
        assertEquals("google/gemma-4-e2b", default.model)
        assertEquals("heuristic", heuristic.provider)
    }

    @Test
    fun `legacy run snapshots without a Stage 04 parser pin continue using their source parser`() {
        val objectMapper = jacksonObjectMapper()
        val legacyJson = objectMapper.readTree(factory.toJson(factory.from(factory.parseRequest(null))))
            .deepCopy<ObjectNode>()
        legacyJson.remove("citedPaperParser")
        legacyJson.remove("bibliographyNormalizationPolicy")
        val legacySnapshot = objectMapper.readValue(legacyJson.toString(), AnalysisConfigurationSnapshot::class.java)

        assertEquals(null, legacySnapshot.citedPaperParser)
        assertEquals("grobid", legacySnapshot.citedPaperParserSelection().provider)
        assertEquals("0.9.1-crf", legacySnapshot.citedPaperParserSelection().version)
        assertEquals(null, legacySnapshot.bibliographyNormalizationPolicy)
    }

    @Test
    fun `prefers local Ollama for an omitted embedding selection and keeps feature-hash fallback`() {
        val localOllama = OllamaEmbeddingSettings(
            enabled = true,
            baseUrl = "http://127.0.0.1:11434",
            modelId = "nomic-embed-text:v1.5",
            dimension = 768,
            trustedHosts = setOf("127.0.0.1"),
        )
        val localOllamaFactory = factoryFor(
            providerCatalog = ProviderCatalog.safeDefaults(ollamaEmbeddingSettings = localOllama),
        )

        val ollama = localOllamaFactory.from(localOllamaFactory.parseRequest(jacksonObjectMapper().readTree("{}"))).embedding

        assertEquals("ollama", ollama.provider)
        assertEquals("nomic-embed-text:v1.5", ollama.model)
        assertEquals(768, ollama.embeddingDimension)

        val externalOllamaFactory = factoryFor(
            providerCatalog = ProviderCatalog.safeDefaults(
                ollamaEmbeddingSettings = localOllama.copy(
                    baseUrl = "http://embedding.example:11434",
                    trustedHosts = setOf("localhost"),
                    retentionDisclosure = "External Ollama retention terms have not been verified.",
                ),
            ),
        )
        assertEquals("local", externalOllamaFactory.from(externalOllamaFactory.parseRequest(null)).embedding.provider)
    }

    @Test
    fun `uses the deployment System One default when a request omits the provider`() {
        val layaSettings = LayaSystemOneSettings(
            enabled = true,
            baseUrl = "http://127.0.0.1:8000",
            apiKey = "test-sidecar-key",
            trustedHosts = setOf("127.0.0.1"),
        )
        val layaFactory = factoryFor(
            providerCatalog = ProviderCatalog.safeDefaults(layaSystemOneSettings = layaSettings),
            defaultSystemOneProvider = "laya",
        )

        val request = layaFactory.parseRequest(jacksonObjectMapper().readTree("{}"))
        val snapshot = layaFactory.from(request)

        assertEquals("laya", snapshot.systemOne.provider)
    }

    @Test
    fun `Jev is the configured omitted-provider default only when this run has explicit consent`() {
        val catalog = ProviderCatalog.safeDefaults(
            jevSystemOneSettings = JevSystemOneSettings(apiKey = "server-side-jev-key"),
        )
        val jevFactory = factoryFor(
            providerCatalog = catalog,
            defaultSystemOneProvider = JevSystemOneSettings.PROVIDER_ID,
        )
        val omittedProviderRequest = jevFactory.parseRequest(jacksonObjectMapper().readTree("{}"))

        assertThrows(IllegalArgumentException::class.java) { jevFactory.from(omittedProviderRequest) }
        val approvedRequest = omittedProviderRequest.copy(
            externalProviderConsents = listOf(externalProviderConsent(
                catalog,
                JevSystemOneSettings.PROVIDER_ID,
                listOf(DataCategory.ATOMIC_CLAIMS.id, DataCategory.EVIDENCE_PASSAGES.id),
            )),
        )

        assertEquals("jev", jevFactory.from(approvedRequest).systemOne.provider)
    }

    @Test
    fun `uses mock when the configured Jev default is unavailable`() {
        val unavailableJevCatalog = ProviderCatalog.safeDefaults(
            layaSystemOneSettings = LayaSystemOneSettings.disabled(),
            jevSystemOneSettings = JevSystemOneSettings(apiKey = ""),
        )
        val jevDefaultFactory = factoryFor(
            providerCatalog = unavailableJevCatalog,
            defaultSystemOneProvider = JevSystemOneSettings.PROVIDER_ID,
        )

        assertEquals("mock", jevDefaultFactory.from(jevDefaultFactory.parseRequest(null)).systemOne.provider)
        assertThrows(ProviderNotSelectableException::class.java) {
            jevDefaultFactory.from(RunConfigurationRequest(systemOneProvider = JevSystemOneSettings.PROVIDER_ID))
        }
    }

    @Test
    fun `pins the configured claim-citation pair limit and rejects non-positive limits`() {
        val configured = factoryFor(maxClaimCitationPairs = 12).from(RunConfigurationRequest())

        assertEquals(12, configured.validationLimits.maxClaimCitationPairs)
        assertThrows(IllegalArgumentException::class.java) { factoryFor(maxClaimCitationPairs = 0) }
    }

    @Test
    fun `snapshots the same deterministic aggregation policy for Laya and Jev but not mock`() {
        val layaSettings = LayaSystemOneSettings(
            enabled = true,
            baseUrl = "http://127.0.0.1:8000",
            apiKey = "test-sidecar-key",
            trustedHosts = setOf("127.0.0.1"),
        )
        val jevSettings = JevSystemOneSettings(
            apiKey = "server-side-jev-key",
            retentionDisclosure = "Retention and deletion terms are unknown.",
        )
        val layaFactory = factoryFor(
            providerCatalog = ProviderCatalog.safeDefaults(layaSystemOneSettings = layaSettings),
            defaultSystemOneProvider = LayaSystemOneSettings.PROVIDER_ID,
            evidenceAggregationThresholds = TestEvidenceAggregationThresholds.values,
            systemOneAggregationEnabled = true,
        )
        val layaSnapshot = layaFactory.from(layaFactory.parseRequest(null))
        val jevCatalog = ProviderCatalog.safeDefaults(jevSystemOneSettings = jevSettings)
        val jevFactory = factoryFor(
            providerCatalog = jevCatalog,
            evidenceAggregationThresholds = TestEvidenceAggregationThresholds.values,
            systemOneAggregationEnabled = true,
        )
        val jevSnapshot = jevFactory.from(
            RunConfigurationRequest(
                systemOneProvider = JevSystemOneSettings.PROVIDER_ID,
                externalProviderConsents = listOf(externalProviderConsent(
                    jevCatalog,
                    JevSystemOneSettings.PROVIDER_ID,
                    listOf(DataCategory.ATOMIC_CLAIMS.id, DataCategory.EVIDENCE_PASSAGES.id),
                )),
            ),
        )
        val mockSnapshot = factoryFor(
            evidenceAggregationThresholds = TestEvidenceAggregationThresholds.values,
            systemOneAggregationEnabled = true,
        ).from(RunConfigurationRequest())

        listOf(layaSnapshot, jevSnapshot).forEach { snapshot ->
            assertEquals("PENDING", snapshot.aggregation.executionStatus)
            assertEquals("weighted-evidence-role-scope-design-v1", snapshot.aggregation.verificationPolicyVersion)
            assertEquals("conflict-aware-evidence-strength-v1", snapshot.aggregation.aggregationPolicyVersion)
            assertEquals(0.8, snapshot.aggregation.thresholds?.get("directSupport"))
            assertEquals(0.7, snapshot.aggregation.thresholds?.get("partialSupport"))
            assertEquals(0.8, snapshot.aggregation.thresholds?.get("contradiction"))
            assertEquals(0.08, snapshot.aggregation.thresholds?.get("comparabilityMargin"))
        }
        assertEquals("laya", layaSnapshot.systemOne.provider)
        assertEquals("jev", jevSnapshot.systemOne.provider)
        assertEquals("NOT_RUN", mockSnapshot.aggregation.executionStatus)
        assertEquals(null, mockSnapshot.aggregation.thresholds)
    }

    @Test
    fun `system one aggregation requires explicit thresholds when enabled`() {
        assertThrows(IllegalArgumentException::class.java) {
            factoryFor(systemOneAggregationEnabled = true)
        }
    }

    @Test
    fun `system one aggregation is disabled when its configuration is false`() {
        val layaSettings = LayaSystemOneSettings(
            enabled = true,
            baseUrl = "http://127.0.0.1:8000",
            apiKey = "test-sidecar-key",
            trustedHosts = setOf("127.0.0.1"),
        )
        val layaFactory = factoryFor(
            providerCatalog = ProviderCatalog.safeDefaults(layaSystemOneSettings = layaSettings),
            defaultSystemOneProvider = LayaSystemOneSettings.PROVIDER_ID,
            evidenceAggregationThresholds = TestEvidenceAggregationThresholds.values,
            systemOneAggregationEnabled = false,
        )

        val snapshot = layaFactory.from(layaFactory.parseRequest(null))

        assertEquals("NOT_RUN", snapshot.aggregation.executionStatus)
        assertEquals(null, snapshot.aggregation.thresholds)
    }

    @Test
    fun `pins aggregation thresholds for an eligible provider when the policy is enabled`() {
        val jevSettings = JevSystemOneSettings(apiKey = "server-side-jev-key")
        val catalog = ProviderCatalog.safeDefaults(jevSystemOneSettings = jevSettings)
        val configured = factoryFor(
            providerCatalog = catalog,
            evidenceAggregationThresholds = TestEvidenceAggregationThresholds.values,
            systemOneAggregationEnabled = true,
        ).from(
            RunConfigurationRequest(
                systemOneProvider = JevSystemOneSettings.PROVIDER_ID,
                externalProviderConsents = listOf(externalProviderConsent(
                    catalog,
                    JevSystemOneSettings.PROVIDER_ID,
                    listOf(DataCategory.ATOMIC_CLAIMS.id, DataCategory.EVIDENCE_PASSAGES.id),
                )),
            ),
        )

        assertEquals("PENDING", configured.aggregation.executionStatus)
        assertEquals("weighted-evidence-role-scope-design-v1", configured.aggregation.verificationPolicyVersion)
        assertEquals("conflict-aware-evidence-strength-v1", configured.aggregation.aggregationPolicyVersion)
        assertEquals(0.8, configured.aggregation.thresholds?.get("directSupport"))
        assertEquals(0.7, configured.aggregation.thresholds?.get("partialSupport"))
        assertEquals(0.8, configured.aggregation.thresholds?.get("contradiction"))
        assertEquals(0.08, configured.aggregation.thresholds?.get("comparabilityMargin"))
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
        legacyTree.remove(listOf("referenceResolution", "aggregation"))

        val legacySnapshot = objectMapper.treeToValue(legacyTree, AnalysisConfigurationSnapshot::class.java)

        assertEquals("NOT_RUN", legacySnapshot.referenceResolution.executionStatus)
        assertEquals(null, legacySnapshot.referenceResolution.provider)
        assertEquals(null, legacySnapshot.referenceResolution.scorePolicyVersion)
        assertEquals(null, legacySnapshot.referenceResolution.confidenceThreshold)
        assertEquals("NOT_RUN", legacySnapshot.aggregation.executionStatus)
        assertEquals(null, legacySnapshot.aggregation.thresholds)
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
            factoryFor().from(RunConfigurationRequest(systemOneProvider = "unclassified-provider"))
        }
    }

    @Test
    fun `external provider availability does not require terms review but requires a classifiable payload`() {
        val external = ProviderCatalog(listOf(
            ProviderRegistration(
                CLAIM_EXTRACTOR_ROLE,
                "external-with-unknown-terms",
                "External provider",
                "v1",
                null,
                ProviderTrustBoundary.EXTERNAL,
                true,
                setOf(DataCategory.CITATION_CONTEXT),
            ),
        )).requireSelectable(CLAIM_EXTRACTOR_ROLE, "external-with-unknown-terms")
        assertEquals("EXTERNAL", external.trustBoundary.id)
        val directoryOption = ProviderCatalog(listOf(external)).directory().providers.getValue(CLAIM_EXTRACTOR_ROLE).single()
        assertTrue(directoryOption.retentionDisclosure!!.contains("Retention and deletion details are unknown"))
        assertTrue(directoryOption.retentionDisclosureFingerprint!!.matches(Regex("[0-9a-f]{64}")))

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
        val selected = RunConfigurationRequest(claimExtractorProvider = "configured-llm")
        assertThrows(IllegalArgumentException::class.java) { externalFactory.from(selected) }

        val approved = selected.copy(
            externalProviderConsents = listOf(externalProviderConsent(
                configuredExternalProviderCatalog(),
                "configured-llm",
                listOf("citation_context"),
            )),
        )
        val snapshot = externalFactory.from(approved)
        assertEquals("EXTERNAL", snapshot.claimExtractor.trustBoundary)
        assertEquals(listOf("citation_context"), snapshot.externalProviderConsents.single().dataCategories)
        assertEquals("Retention and deletion terms for this controlled test provider.", snapshot.externalProviderConsents.single().retentionDisclosure)

        assertThrows(IllegalArgumentException::class.java) {
            externalFactory.from(approved.copy(externalProviderConsents = listOf(externalProviderConsent(
                configuredExternalProviderCatalog(), "configured-llm", listOf("atomic_claims"),
            ))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            externalFactory.from(approved.copy(externalProviderConsents = listOf(externalProviderConsent(
                configuredExternalProviderCatalog(), "configured-llm", listOf("citation_context", "citation_context"),
            ))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            externalFactory.from(approved.copy(externalProviderConsents = listOf(
                com.papertrail.api.analysis.http.ExternalProviderConsentRequest("mock", listOf("citation_context"), "a".repeat(64)),
                externalProviderConsent(configuredExternalProviderCatalog(), "configured-llm", listOf("citation_context")),
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
                externalProviderConsents = listOf(externalProviderConsent(
                    configuredExternalProviderCatalog(), "crossref", listOf("bibliographic_metadata"),
                )),
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
        val fingerprint = externalProviderConsent(
            configuredExternalProviderCatalog(), "configured-llm", listOf("citation_context"),
        ).retentionDisclosureFingerprint
        val parsed = externalFactory.parseRequest(
            jacksonObjectMapper().readTree(
                """{"claimExtractorProvider":"configured-llm","externalProviderConsents":[{"providerId":"configured-llm","dataCategories":["citation_context"],"retentionDisclosureFingerprint":"$fingerprint"}]}""",
            ),
        )
        assertEquals(listOf("citation_context"), parsed.externalProviderConsents.single().dataCategories)
        assertEquals("configured-llm", externalFactory.from(parsed).claimExtractor.provider)
        assertThrows(IllegalArgumentException::class.java) {
            externalFactory.from(parsed.copy(externalProviderConsents = listOf(
                parsed.externalProviderConsents.single().copy(retentionDisclosureFingerprint = "0".repeat(64)),
            )))
        }
        assertThrows(IllegalArgumentException::class.java) {
            externalFactory.parseRequest(jacksonObjectMapper().readTree(
                """{"claimExtractorProvider":"configured-llm","externalProviderConsents":[{"providerId":"configured-llm","dataCategories":["citation_context"],"retentionDisclosure":"client text","retentionDisclosureFingerprint":"$fingerprint"}]}""",
            ))
        }
        val unknownCategory = externalFactory.parseRequest(
            jacksonObjectMapper().readTree(
                """{"claimExtractorProvider":"configured-llm","externalProviderConsents":[{"providerId":"configured-llm","dataCategories":["made_up_category"],"retentionDisclosureFingerprint":"$fingerprint"}]}""",
            ),
        )
        assertThrows(IllegalArgumentException::class.java) { externalFactory.from(unknownCategory) }
    }

    @Test
    fun `provider call gate checks actual request categories before invoking the outbound action`() {
        val externalFactory = factoryFor()
        val snapshot = externalFactory.from(
            RunConfigurationRequest(
                claimExtractorProvider = "configured-llm",
                externalProviderConsents = listOf(externalProviderConsent(
                    configuredExternalProviderCatalog(), "configured-llm", listOf("citation_context"),
                )),
            ),
        )
        val gate = ProviderCallGate(configuredExternalProviderCatalog())
        var outboundCallStarted = false

        assertThrows(ProviderCallRejectedException::class.java) {
            gate.call(
                CLAIM_EXTRACTOR_ROLE,
                "configured-llm",
                providerCallPayload(DataCategory.CITATION_CONTEXT, DataCategory.SOURCE_DOCUMENT_TEXT),
                snapshot,
            ) { _ -> outboundCallStarted = true }
        }
        assertFalse(outboundCallStarted)
        assertThrows(ProviderCallRejectedException::class.java) {
            gate.call(
                CLAIM_EXTRACTOR_ROLE,
                "configured-llm",
                providerCallPayload(DataCategory.CITATION_CONTEXT),
                snapshot.copy(externalProviderConsents = emptyList()),
            ) { _ -> outboundCallStarted = true }
        }
        assertThrows(ProviderCallRejectedException::class.java) {
            gate.call(
                CLAIM_EXTRACTOR_ROLE,
                "configured-llm",
                providerCallPayload(DataCategory.CITATION_CONTEXT),
                snapshot.copy(claimExtractor = snapshot.claimExtractor.copy(dataCategories = listOf("source_document_text"))),
            ) { _ -> outboundCallStarted = true }
        }
        assertThrows(ProviderCallRejectedException::class.java) {
            gate.call(
                CLAIM_EXTRACTOR_ROLE,
                "configured-llm",
                ProviderCallPayload(emptyMap()),
                snapshot,
            ) { _ -> outboundCallStarted = true }
        }
        assertFalse(outboundCallStarted)

        assertEquals("response", gate.call(
            CLAIM_EXTRACTOR_ROLE,
            "configured-llm",
            providerCallPayload(DataCategory.CITATION_CONTEXT),
            snapshot,
        ) { payload ->
            outboundCallStarted = true
            assertEquals(setOf(DataCategory.CITATION_CONTEXT), payload.dataCategories)
            assertEquals("citation_context content", payload.contentByCategory.getValue(DataCategory.CITATION_CONTEXT).asText())
            "response"
        })
        assertTrue(outboundCallStarted)

        outboundCallStarted = false
        assertThrows(ProviderCallRejectedException::class.java) {
            gate.callAvailabilityCheck(
                CLAIM_EXTRACTOR_ROLE,
                "configured-llm",
                snapshot.copy(externalProviderConsents = emptyList()),
            ) { outboundCallStarted = true }
        }
        assertFalse(outboundCallStarted)
        assertEquals("available", gate.callAvailabilityCheck(
            CLAIM_EXTRACTOR_ROLE,
            "configured-llm",
            snapshot,
        ) {
            outboundCallStarted = true
            "available"
        })
        assertTrue(outboundCallStarted)
    }

    @Test
    fun `disabled and unclassified providers are rejected before an outbound action`() {
        val snapshot = factory.from(RunConfigurationRequest())
        val disabledGate = ProviderCallGate(ProviderCatalog.safeDefaults())
        val unclassifiedProviderGate = ProviderCallGate(configuredExternalProviderCatalog())
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
            unclassifiedProviderGate.call(
                SYSTEM_ONE_ROLE,
                "unclassified-provider",
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
        vectorCandidateLimit: Int = 3,
        lexicalCandidateLimit: Int = 3,
        finalCandidateLimit: Int = 3,
        reciprocalRankFusionConstant: Int = 60,
        evidenceAggregationThresholds: EvidenceAggregationThresholds? = null,
        systemOneAggregationEnabled: Boolean = false,
        maxClaimCitationPairs: Int = ValidationLimitsSnapshot.DEFAULT_MAX_CLAIM_CITATION_PAIRS,
        providerCatalog: ProviderCatalog = configuredExternalProviderCatalog(),
        defaultSystemOneProvider: String = "mock",
        defaultClaimExtractorProvider: String = "heuristic",
    ): RunConfigurationFactory = RunConfigurationFactory(
        providerCatalog = providerCatalog,
        parserId = "grobid",
        parserVersion = "0.9.1-crf",
        languageDetectorVersion = "0.6",
        limits = ValidationLimitsSnapshot(
            maxUploadBytes = 52_428_800,
            maxPages = 500,
            maxExtractedCharacters = 5_000_000,
            maxExtractedCharactersPerPage = 100_000,
            minimumExtractedCharacters = 100,
            minimumLanguageConfidence = 0.65,
            maxClaimCitationPairs = maxClaimCitationPairs,
        ),
        retrievalProfileId = retrievalProfileId,
        vectorCandidateLimit = vectorCandidateLimit,
        lexicalCandidateLimit = lexicalCandidateLimit,
        finalCandidateLimit = finalCandidateLimit,
        reciprocalRankFusionConstant = reciprocalRankFusionConstant,
        evidenceAggregationThresholds = evidenceAggregationThresholds,
        systemOneAggregationEnabled = systemOneAggregationEnabled,
        defaultSystemOneProvider = defaultSystemOneProvider,
        defaultClaimExtractorProvider = defaultClaimExtractorProvider,
    )

    @Test
    fun `rejects unknown configuration fields instead of silently ignoring them`() {
        val configuration = jacksonObjectMapper().readTree("""{"claimExtractorProvider":"heuristic","unexpected":true}""")
        assertThrows(IllegalArgumentException::class.java) { factory.parseRequest(configuration) }
    }
}
