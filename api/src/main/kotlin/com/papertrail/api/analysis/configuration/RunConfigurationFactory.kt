package com.papertrail.api.analysis.configuration

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
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
import com.papertrail.api.scholarly.references.resolver.ScholarlyMetadataMatcher
import com.papertrail.api.evidence.verification.domain.EvidenceAggregationPolicy
import com.papertrail.api.evidence.verification.domain.EvidenceAggregationThresholds
import com.papertrail.api.evidence.verification.domain.EvidenceJudgement
import com.papertrail.api.evidence.verification.provider.LayaSystemOneSettings

class RunConfigurationFactory(
    private val objectMapper: ObjectMapper,
    private val providerCatalog: ProviderCatalog,
    private val parserId: String,
    private val parserVersion: String,
    private val languageDetectorVersion: String,
    private val limits: ValidationLimitsSnapshot,
    private val referenceResolutionPolicyVersion: String = ScholarlyMetadataMatcher.POLICY_VERSION,
    private val referenceResolutionConfidenceThreshold: Double = 0.9,
    private val retrievalProfileId: String = "postgres-hybrid-rrf-v1",
    private val vectorCandidateLimit: Int = 10,
    private val lexicalCandidateLimit: Int = 10,
    private val finalCandidateLimit: Int = 5,
    private val reciprocalRankFusionConstant: Int = 60,
    private val evidenceAggregationThresholds: EvidenceAggregationThresholds? = null,
    private val localLayaAggregationEnabled: Boolean = false,
    private val defaultSystemOneProvider: String = "mock",
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
        require(defaultSystemOneProvider.isNotBlank()) { "Default System One provider must be configured." }
        require(!localLayaAggregationEnabled || evidenceAggregationThresholds != null) {
            "Local Laya aggregation requires explicitly configured thresholds."
        }
    }

    fun parseRequest(node: JsonNode?): RunConfigurationRequest {
        if (node == null || node.isNull) return RunConfigurationRequest()
        require(node.isObject) { "Analysis configuration must be a JSON object." }
        val allowed = setOf("claimExtractorProvider", "embeddingProvider", "systemOneProvider", "scholarlyMetadataProvider", "openAccessProvider", "externalProviderConsents")
        val supplied = node.fieldNames().asSequence().toSet()
        require(supplied.all { it in allowed }) { "Analysis configuration contains unsupported fields." }
        fun provider(name: String, default: String): String {
            val value = node.get(name) ?: return default
            require(value.isTextual && value.asText().isNotBlank()) { "Analysis configuration field '$name' must be a non-empty string." }
            return value.asText()
        }
        fun optionalSystemOneProvider(): String? {
            val value = node.get("systemOneProvider") ?: return null
            require(value.isTextual && value.asText().isNotBlank()) {
                "Analysis configuration field 'systemOneProvider' must be a non-empty string."
            }
            return value.asText()
        }
        val providerConsents = node.get("externalProviderConsents")?.let { consents ->
            require(consents.isArray) { "Analysis configuration field 'externalProviderConsents' must be an array." }
            consents.map { consent ->
                require(consent.isObject) { "Each external provider consent must be an object." }
                val consentFields = consent.fieldNames().asSequence().toSet()
                require(consentFields == setOf("providerId", "dataCategories")) {
                    "External provider consent must contain only providerId and dataCategories."
                }
                val providerId = consent.get("providerId")
                require(providerId.isTextual && providerId.asText().isNotBlank()) {
                    "External provider consent providerId must be a non-empty string."
                }
                val categories = consent.get("dataCategories")
                require(categories.isArray && categories.all { it.isTextual && it.asText().isNotBlank() }) {
                    "External provider consent dataCategories must be an array of non-empty strings."
                }
                ExternalProviderConsentSnapshot(providerId.asText(), categories.map { it.asText() })
            }
        } ?: emptyList()
        return RunConfigurationRequest(
            claimExtractorProvider = provider("claimExtractorProvider", "heuristic"),
            embeddingProvider = provider("embeddingProvider", "local"),
            systemOneProvider = optionalSystemOneProvider(),
            scholarlyMetadataProvider = provider("scholarlyMetadataProvider", "recorded-fixtures"),
            openAccessProvider = provider("openAccessProvider", "recorded-fixtures"),
            externalProviderConsents = providerConsents,
        )
    }

    /** Omitted Laya defaults fall back only when Laya is not selectable; explicit choices fail closed. */
    private fun defaultSystemOneRegistration(requestedProvider: String?): ProviderRegistration {
        if (requestedProvider != null) return providerCatalog.requireSelectable(SYSTEM_ONE_ROLE, requestedProvider)

        return try {
            providerCatalog.requireSelectable(SYSTEM_ONE_ROLE, defaultSystemOneProvider)
        } catch (exception: ProviderNotSelectableException) {
            if (defaultSystemOneProvider != LayaSystemOneSettings.PROVIDER_ID) throw exception
            providerCatalog.requireSelectable(SYSTEM_ONE_ROLE, "mock")
        }
    }

    fun from(request: RunConfigurationRequest): AnalysisConfigurationSnapshot {
        val selected = listOf(
            providerCatalog.requireSelectable(CLAIM_EXTRACTOR_ROLE, request.claimExtractorProvider),
            providerCatalog.requireSelectable(EMBEDDING_ROLE, request.embeddingProvider),
            defaultSystemOneRegistration(request.systemOneProvider),
            providerCatalog.requireSelectable(SCHOLARLY_METADATA_ROLE, request.scholarlyMetadataProvider),
            providerCatalog.requireSelectable(OPEN_ACCESS_ROLE, request.openAccessProvider),
        )
        val requiredConsents = selected
            .filter { it.trustBoundary == ProviderTrustBoundary.EXTERNAL }
            .groupBy(ProviderRegistration::providerId)
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
            ExternalProviderConsentSnapshot(providerId, requiredCategories.map(DataCategory::id).sorted())
        }.sortedBy(ExternalProviderConsentSnapshot::providerId)
        return AnalysisConfigurationSnapshot(
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
            openAccessRetentionDisclosure = selected[4].retentionDisclosure,
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
        )
    }

    private fun aggregationThresholdsFor(systemOneProvider: String): EvidenceAggregationThresholds? {
        if (localLayaAggregationEnabled && systemOneProvider != LayaSystemOneSettings.PROVIDER_ID) return null
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
    )

    fun toJson(snapshot: AnalysisConfigurationSnapshot): String = objectMapper.writeValueAsString(snapshot)
}
