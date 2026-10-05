package com.papertrail.api.analysis.configuration

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.papertrail.api.analysis.http.ExternalProviderConsentRequest
import com.papertrail.api.analysis.http.RunConfigurationRequest
import com.papertrail.api.infrastructure.providers.CLAIM_EXTRACTOR_ROLE
import com.papertrail.api.infrastructure.providers.DataCategory
import com.papertrail.api.infrastructure.providers.EMBEDDING_ROLE
import com.papertrail.api.infrastructure.providers.ProviderCatalog
import com.papertrail.api.infrastructure.providers.ProviderNotSelectableException
import com.papertrail.api.infrastructure.providers.ProviderRegistration
import com.papertrail.api.infrastructure.providers.ProviderTrustBoundary
import com.papertrail.api.infrastructure.providers.OPEN_ACCESS_ROLE
import com.papertrail.api.infrastructure.providers.SCHOLARLY_METADATA_ROLE
import com.papertrail.api.infrastructure.providers.SYSTEM_ONE_ROLE
import com.papertrail.api.evidence.domain.EmbeddingProfile
import com.papertrail.api.evidence.embedding.OllamaEmbeddingSettings
import com.papertrail.api.scholarly.references.resolver.ScholarlyMetadataMatcher
import com.papertrail.api.evidence.verification.domain.EvidenceAggregationPolicy
import com.papertrail.api.evidence.verification.domain.EvidenceAggregationThresholds
import com.papertrail.api.evidence.verification.domain.EvidenceJudgement
import com.papertrail.api.evidence.verification.provider.JevSystemOneSettings
import com.papertrail.api.evidence.verification.provider.LayaSystemOneSettings

class RunConfigurationFactory(
    private val objectMapper: ObjectMapper,
    private val providerCatalog: ProviderCatalog,
    private val parserId: String,
    private val parserVersion: String,
    private val languageDetectorVersion: String,
    private val limits: ValidationLimitsSnapshot,
    private val referenceResolutionPolicyVersion: String = ScholarlyMetadataMatcher.POLICY_VERSION,
    private val referenceResolutionConfidenceThreshold: Double = 0.25,
    private val retrievalProfileId: String = "postgres-hybrid-rrf-v1",
    private val vectorCandidateLimit: Int = 3,
    private val lexicalCandidateLimit: Int = 3,
    private val finalCandidateLimit: Int = 3,
    private val reciprocalRankFusionConstant: Int = 60,
    private val evidenceAggregationThresholds: EvidenceAggregationThresholds? = null,
    private val systemOneAggregationEnabled: Boolean = false,
    private val defaultSystemOneProvider: String = JevSystemOneSettings.PROVIDER_ID,
    private val defaultClaimExtractorProvider: String = "heuristic",
    private val citedPaperParserId: String = "docling",
    private val citedPaperParserVersion: String = "1.30.0",
) {
    init {
        require(referenceResolutionPolicyVersion.isNotBlank()) { "Reference resolution policy version must be configured." }
        require(referenceResolutionConfidenceThreshold in 0.0..1.0) { "Reference resolution threshold must be between zero and one." }
        require(retrievalProfileId.isNotBlank() && retrievalProfileId.length <= 80) {
            "Retrieval profile ID must contain between one and 80 characters."
        }
        require(vectorCandidateLimit > 0 && lexicalCandidateLimit > 0 && finalCandidateLimit > 0) {
            "Evidence retrieval candidate limits must be positive."
        }
        require(reciprocalRankFusionConstant > 0) { "Reciprocal-rank fusion constant must be positive." }
        require(limits.maxClaimCitationPairs > 0) { "The claim-citation pair limit must be positive." }
        require(defaultClaimExtractorProvider.isNotBlank()) { "Default claim-analysis provider must be configured." }
        require(citedPaperParserId.isNotBlank() && citedPaperParserVersion.isNotBlank()) {
            "The Cited Paper parser identity and version must be configured."
        }
        require(!systemOneAggregationEnabled || evidenceAggregationThresholds != null) {
            "System One aggregation requires explicitly configured thresholds."
        }
    }

    fun parseRequest(node: JsonNode?): RunConfigurationRequest {
        if (node == null || node.isNull) return RunConfigurationRequest()
        require(node.isObject) { "Analysis configuration must be a JSON object." }
        val allowed = setOf("captureExecution", "claimExtractorProvider", "embeddingProvider", "systemOneProvider", "scholarlyMetadataProvider", "openAccessProvider", "externalProviderConsents")
        val supplied = node.fieldNames().asSequence().toSet()
        require(supplied.all { it in allowed }) { "Analysis configuration contains unsupported fields." }
        fun provider(name: String, default: String): String {
            val value = node.get(name) ?: return default
            require(value.isTextual && value.asText().isNotBlank()) { "Analysis configuration field '$name' must be a non-empty string." }
            return value.asText()
        }
        fun optionalProvider(name: String): String? {
            val value = node.get(name) ?: return null
            require(value.isTextual && value.asText().isNotBlank()) {
                "Analysis configuration field '$name' must be a non-empty string."
            }
            return value.asText()
        }
        val captureExecution = node.get("captureExecution")?.let { capture ->
            require(capture.isBoolean) { "Analysis configuration field 'captureExecution' must be a boolean." }
            capture.asBoolean()
        } ?: true
        val providerConsents = node.get("externalProviderConsents")?.let { consents ->
            require(consents.isArray) { "Analysis configuration field 'externalProviderConsents' must be an array." }
            consents.map { consent ->
                require(consent.isObject) { "Each external provider consent must be an object." }
                val consentFields = consent.fieldNames().asSequence().toSet()
                require(consentFields == setOf("providerId", "dataCategories", "retentionDisclosureFingerprint")) {
                    "External provider consent must contain providerId, dataCategories, and retentionDisclosureFingerprint."
                }
                val providerId = consent.get("providerId")
                require(providerId.isTextual && providerId.asText().isNotBlank()) {
                    "External provider consent providerId must be a non-empty string."
                }
                val categories = consent.get("dataCategories")
                require(categories.isArray && categories.all { it.isTextual && it.asText().isNotBlank() }) {
                    "External provider consent dataCategories must be an array of non-empty strings."
                }
                val disclosureFingerprint = consent.get("retentionDisclosureFingerprint")
                require(disclosureFingerprint.isTextual && disclosureFingerprint.asText().matches(Regex("[0-9a-f]{64}"))) {
                    "External provider consent retentionDisclosureFingerprint must be a SHA-256 fingerprint."
                }
                ExternalProviderConsentRequest(providerId.asText(), categories.map { it.asText() }, disclosureFingerprint.asText())
            }
        } ?: emptyList()
        return RunConfigurationRequest(
            captureExecution = captureExecution,
            claimExtractorProvider = optionalProvider("claimExtractorProvider"),
            embeddingProvider = optionalProvider("embeddingProvider"),
            systemOneProvider = optionalProvider("systemOneProvider"),
            scholarlyMetadataProvider = provider("scholarlyMetadataProvider", "recorded-fixtures"),
            openAccessProvider = provider("openAccessProvider", "recorded-fixtures"),
            externalProviderConsents = providerConsents,
        )
    }

    /** Prefer local Ollama when available, but keep the safe feature-hash fallback for other deployments. */
    private fun defaultEmbeddingRegistration(requestedProvider: String?): ProviderRegistration {
        if (requestedProvider != null) return providerCatalog.requireSelectable(EMBEDDING_ROLE, requestedProvider)

        val ollama = try {
            providerCatalog.requireSelectable(EMBEDDING_ROLE, OllamaEmbeddingSettings.PROVIDER_ID)
        } catch (_: ProviderNotSelectableException) {
            null
        }
        return ollama?.takeIf { it.trustBoundary == ProviderTrustBoundary.LOCAL }
            ?: providerCatalog.requireSelectable(EMBEDDING_ROLE, "local")
    }

    /** Unavailable Jev or Laya defaults fall back to mock; explicit unavailable choices fail closed. */
    private fun defaultSystemOneRegistration(requestedProvider: String?): ProviderRegistration {
        if (requestedProvider != null) return providerCatalog.requireSelectable(SYSTEM_ONE_ROLE, requestedProvider)

        return try {
            providerCatalog.requireSelectable(SYSTEM_ONE_ROLE, defaultSystemOneProvider)
        } catch (exception: ProviderNotSelectableException) {
            if (defaultSystemOneProvider !in setOf(LayaSystemOneSettings.PROVIDER_ID, JevSystemOneSettings.PROVIDER_ID)) {
                throw exception
            }
            providerCatalog.requireSelectable(SYSTEM_ONE_ROLE, "mock")
        }
    }

    fun from(request: RunConfigurationRequest): AnalysisConfigurationSnapshot {
        val selected = listOf(
            providerCatalog.requireSelectable(
                CLAIM_EXTRACTOR_ROLE,
                request.claimExtractorProvider ?: defaultClaimExtractorProvider,
            ),
            defaultEmbeddingRegistration(request.embeddingProvider),
            defaultSystemOneRegistration(request.systemOneProvider),
            providerCatalog.requireSelectable(SCHOLARLY_METADATA_ROLE, request.scholarlyMetadataProvider),
            providerCatalog.requireSelectable(OPEN_ACCESS_ROLE, request.openAccessProvider),
        )
        val externalRegistrations = selected
            .filter { it.trustBoundary == ProviderTrustBoundary.EXTERNAL }
            .groupBy(ProviderRegistration::providerId)
        val requiredConsents = externalRegistrations
            .mapValues { (_, providers) -> providers.flatMap(ProviderRegistration::dataCategories).toSet() }
        val suppliedConsents = request.externalProviderConsents.associateBy { it.providerId }
        require(suppliedConsents.size == request.externalProviderConsents.size) {
            "External provider consent must be listed once per provider."
        }
        require(suppliedConsents.keys == requiredConsents.keys) {
            "External provider consent must match the external providers selected for this Analysis Run."
        }
        val embeddingSelection = selected[1].toSelection()
        val embeddingProfile = EmbeddingProfile.from(embeddingSelection)
        val aggregationThresholds = aggregationThresholdsFor(selected[2].providerId)
        val consentSnapshots = requiredConsents.map { (providerId, requiredCategories) ->
            val consent = suppliedConsents.getValue(providerId)
            val suppliedCategories = consent.dataCategories.map { id ->
                DataCategory.fromId(id)
                    ?: throw IllegalArgumentException("Unknown data category '$id' in provider consent.")
            }
            require(suppliedCategories.distinct().size == suppliedCategories.size) {
                "Provider consent data categories must not contain duplicates."
            }
            require(suppliedCategories.toSet() == requiredCategories) {
                "Provider '$providerId' consent must exactly match its declared payload categories."
            }
            val registrations = externalRegistrations.getValue(providerId)
            val disclosure = requireNotNull(registrations.first().consentDisclosure())
            val disclosureFingerprint = requireNotNull(registrations.first().consentDisclosureFingerprint())
            require(registrations.all { it.consentDisclosure() == disclosure && it.consentDisclosureFingerprint() == disclosureFingerprint }) {
                "Provider '$providerId' has conflicting retention disclosures across selected roles."
            }
            require(consent.retentionDisclosureFingerprint == disclosureFingerprint) {
                "Provider '$providerId' retention disclosure changed; refresh the provider directory before consenting."
            }
            ExternalProviderConsentSnapshot(providerId, requiredCategories.map(DataCategory::id).sorted(), disclosure)
        }.sortedBy(ExternalProviderConsentSnapshot::providerId)
        return AnalysisConfigurationSnapshot(
            captureExecution = request.captureExecution,
            claimExtractor = selected[0].toSelection(),
            embedding = embeddingSelection,
            retrieval = RetrievalConfigurationSnapshot(
                profileId = retrievalProfileId,
                vectorCandidateLimit = vectorCandidateLimit,
                lexicalCandidateLimit = lexicalCandidateLimit,
                finalCandidateLimit = finalCandidateLimit,
                reciprocalRankFusionConstant = reciprocalRankFusionConstant,
                embeddingProfileHash = embeddingProfile.profileHash,
            ),
            systemOne = selected[2].toSelection(),
            sourceParser = ProviderSelection(parserId, parserVersion),
            languageDetector = ProviderSelection("optimaize", languageDetectorVersion),
            validationLimits = limits,
            openAccess = selected[4].toSelection(),
            openAccessProviderConfigurationFingerprint = selected[4].payloadConfigurationFingerprint,
            openAccessRetentionDisclosure = consentSnapshots
                .singleOrNull { it.providerId == selected[4].providerId }
                ?.retentionDisclosure,
            referenceResolution = ReferenceResolutionSnapshot(
                executionStatus = "PENDING",
                provider = selected[3].toSelection(),
                providerConfigurationFingerprint = selected[3].payloadConfigurationFingerprint,
                scorePolicyVersion = referenceResolutionPolicyVersion,
                confidenceThreshold = referenceResolutionConfidenceThreshold,
            ),
            aggregation = AggregationPolicySnapshot(
                executionStatus = if (aggregationThresholds == null) "NOT_RUN" else "PENDING",
                verificationPolicyVersion = aggregationThresholds?.let { EvidenceJudgement.STRENGTH_RUBRIC_VERSION },
                aggregationPolicyVersion = aggregationThresholds?.let { EvidenceAggregationPolicy.POLICY_VERSION },
                thresholds = aggregationThresholds?.asMap(),
            ),
            externalProviderConsents = consentSnapshots,
            citedPaperParser = ProviderSelection(citedPaperParserId, citedPaperParserVersion),
        )
    }

    private fun aggregationThresholdsFor(systemOneProvider: String): EvidenceAggregationThresholds? {
        if (!systemOneAggregationEnabled && systemOneProvider == "mock") return evidenceAggregationThresholds
        if (!systemOneAggregationEnabled || systemOneProvider !in AGGREGATABLE_SYSTEM_ONE_PROVIDERS) return null
        return evidenceAggregationThresholds
    }

    private fun ProviderRegistration.toSelection(): ProviderSelection = ProviderSelection(
        provider = providerId,
        version = version,
        model = model,
        trustBoundary = trustBoundary.id,
        dataCategories = dataCategories.map(DataCategory::id).sorted(),
        configurationFingerprint = configurationFingerprint,
        embeddingDimension = embeddingDimension,
        retentionDisclosure = retentionDisclosure,
        targetSelectionPolicyVersion = targetSelectionPolicyVersion,
        promptVersion = promptVersion,
        outputMappingVersion = outputMappingVersion,
    )

    fun toJson(snapshot: AnalysisConfigurationSnapshot): String = objectMapper.writeValueAsString(snapshot)

    companion object {
        private val AGGREGATABLE_SYSTEM_ONE_PROVIDERS = setOf(
            LayaSystemOneSettings.PROVIDER_ID,
            JevSystemOneSettings.PROVIDER_ID,
        )
    }
}
