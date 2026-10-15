package com.papertrail.api.infrastructure.providers

import com.papertrail.api.analysis.configuration.AnalysisConfigurationSnapshot
import com.papertrail.api.analysis.configuration.ProviderChainSnapshot

class ProviderCallGate(private val catalog: ProviderCatalog) {
    fun <T> call(
        role: String,
        providerId: String,
        payload: ProviderCallPayload,
        configuration: AnalysisConfigurationSnapshot,
        sendRequest: (ProviderCallPayload) -> T,
    ): T {
        val registration = requireMatchingSelection(role, providerId, configuration)
        val actualPayloadCategories = payload.dataCategories
        if (actualPayloadCategories.any { it !in registration.dataCategories }) {
            throw ProviderCallRejectedException("Provider '$providerId' request contains an unclassified data category.")
        }
        if (registration.trustBoundary == ProviderTrustBoundary.EXTERNAL && actualPayloadCategories.isEmpty()) {
            throw ProviderCallRejectedException("External provider '$providerId' request has no classified payload categories.")
        }
        if (registration.trustBoundary == ProviderTrustBoundary.EXTERNAL) {
            requireConsent(role, registration, actualPayloadCategories, configuration)
        }
        return sendRequest(payload)
    }

    /** Authorizes one provider in a pinned chain without consulting the legacy scalar selection. */
    fun <T> callProviderChain(
        chainId: String,
        providerId: String,
        payload: ProviderCallPayload,
        configuration: AnalysisConfigurationSnapshot,
        sendRequest: (ProviderCallPayload) -> T,
    ): T {
        authorizeProviderChain(chainId, providerId, payload, configuration)
        return sendRequest(payload)
    }

    /** Checks chain selection, request categories, and matching consent without invoking a provider. */
    fun authorizeProviderChain(
        chainId: String,
        providerId: String,
        payload: ProviderCallPayload,
        configuration: AnalysisConfigurationSnapshot,
    ) {
        val registration = requireMatchingProviderChainSelection(chainId, providerId, configuration)
        val actualPayloadCategories = payload.dataCategories
        if (actualPayloadCategories.any { it !in registration.dataCategories }) {
            throw ProviderCallRejectedException("Provider '$providerId' request contains an unclassified data category.")
        }
        if (actualPayloadCategories.isEmpty()) {
            throw ProviderCallRejectedException("External provider '$providerId' request has no classified payload categories.")
        }
        requireProviderChainConsent(registration, configuration)
    }

    /** Authorizes a content-free availability probe while preserving run selection and external consent checks. */
    fun <T> callAvailabilityCheck(
        role: String,
        providerId: String,
        configuration: AnalysisConfigurationSnapshot,
        checkAvailability: () -> T,
    ): T {
        val registration = requireMatchingSelection(role, providerId, configuration)
        if (registration.trustBoundary == ProviderTrustBoundary.EXTERNAL) {
            requireConsent(role, registration, registration.dataCategories, configuration)
        }
        return checkAvailability()
    }

    private fun requireMatchingProviderChainSelection(
        chainId: String,
        providerId: String,
        configuration: AnalysisConfigurationSnapshot,
    ): ProviderRegistration {
        val referenceResolution = configuration.referenceResolution
        val chain = referenceResolution.providerChain
            ?: throw ProviderCallRejectedException("No provider chain was configured for this Analysis Run.")
        if (referenceResolution.provider != null || chainId != ProviderChainSnapshot.CHAIN_ID || chain.chainId != chainId) {
            throw ProviderCallRejectedException("Provider chain '$chainId' was not pinned for this Analysis Run.")
        }
        if (chain.mappingVersion != ProviderChainSnapshot.MAPPING_VERSION || chain.policyVersion != referenceResolution.scorePolicyVersion) {
            throw ProviderCallRejectedException("Provider chain '$chainId' policy or mapping changed after this Analysis Run was created.")
        }
        if (chain.providers.map { it.provider } != ProviderChainSnapshot.PROVIDER_IDS) {
            throw ProviderCallRejectedException("Provider chain '$chainId' does not contain the fixed provider order.")
        }
        val registrations = try {
            catalog.requirePinnedProviderChain(chainId)
        } catch (exception: ProviderNotSelectableException) {
            throw ProviderCallRejectedException(exception.message ?: "Provider chain configuration is unavailable.")
        }
        val registration = registrations.singleOrNull { it.providerId == providerId }
            ?: throw ProviderCallRejectedException("Provider '$providerId' is not part of provider chain '$chainId'.")
        val selected = chain.providers.single { it.provider == providerId }
        if (selected.version != registration.version || selected.model != registration.model ||
            selected.trustBoundary != registration.trustBoundary.id ||
            selected.dataCategories != registration.dataCategories.map(DataCategory::id).sorted() ||
            selected.configurationFingerprint != registration.payloadConfigurationFingerprint ||
            selected.retentionDisclosure != registration.consentDisclosure() ||
            selected.retentionDisclosureFingerprint != registration.consentDisclosureFingerprint()
        ) {
            throw ProviderCallRejectedException("Provider '$providerId' configuration or disclosure changed after this Analysis Run was created.")
        }
        return registration
    }

    private fun requireProviderChainConsent(
        registration: ProviderRegistration,
        configuration: AnalysisConfigurationSnapshot,
    ) {
        val providerId = registration.providerId
        val consents = configuration.externalProviderConsents.filter { it.providerId == providerId }
        val consent = consents.singleOrNull()
            ?: throw ProviderCallRejectedException("Provider '$providerId' lacks unique per-run consent.")
        val requiredCategories = registration.dataCategories.map(DataCategory::id).sorted()
        if (consent.dataCategories.distinct().size != consent.dataCategories.size || consent.dataCategories.toSet() != requiredCategories.toSet()) {
            throw ProviderCallRejectedException("Provider '$providerId' consent must exactly match its declared payload categories.")
        }
        if (consent.retentionDisclosure != registration.consentDisclosure()) {
            throw ProviderCallRejectedException("Provider '$providerId' retention disclosure changed after this Analysis Run was created.")
        }
    }

    private fun requireMatchingSelection(
        role: String,
        providerId: String,
        configuration: AnalysisConfigurationSnapshot,
    ): ProviderRegistration {
        if (role == SCHOLARLY_METADATA_ROLE && configuration.referenceResolution.providerChain != null) {
            throw ProviderCallRejectedException("A provider chain cannot be executed through the legacy scalar authorization path.")
        }
        val registration = try {
            catalog.requireSelectable(role, providerId)
        } catch (exception: ProviderNotSelectableException) {
            throw ProviderCallRejectedException(exception.message ?: "Provider is not selectable.")
        }
        val selected = when (role) {
            CLAIM_EXTRACTOR_ROLE -> configuration.claimExtractor
            EMBEDDING_ROLE -> configuration.embedding
            SYSTEM_ONE_ROLE -> configuration.systemOne
            SCHOLARLY_METADATA_ROLE -> configuration.referenceResolution.provider
            OPEN_ACCESS_ROLE -> configuration.openAccess
            else -> throw ProviderCallRejectedException("Provider role '$role' is not supported.")
        } ?: throw ProviderCallRejectedException("Provider role '$role' was not configured for this Analysis Run.")
        if (selected.provider != providerId) {
            throw ProviderCallRejectedException("Provider '$providerId' was not selected for this Analysis Run.")
        }
        val scholarlyConfigurationChanged = role == SCHOLARLY_METADATA_ROLE &&
            configuration.referenceResolution.providerConfigurationFingerprint != registration.payloadConfigurationFingerprint
        val openAccessConfigurationChanged = role == OPEN_ACCESS_ROLE &&
            configuration.openAccessProviderConfigurationFingerprint != registration.payloadConfigurationFingerprint
        val embeddingDimensionChanged = role == EMBEDDING_ROLE &&
            selected.embeddingDimension != registration.embeddingDimension &&
            !(selected.provider == "local" && selected.embeddingDimension == null)
        val embeddingConfigurationChanged = role == EMBEDDING_ROLE &&
            (selected.configurationFingerprint != registration.configurationFingerprint || embeddingDimensionChanged)
        val systemOneConfigurationChanged = role == SYSTEM_ONE_ROLE &&
            selected.configurationFingerprint != registration.configurationFingerprint
        val legacyHeuristicSnapshot = role == CLAIM_EXTRACTOR_ROLE && selected.provider == "heuristic" &&
            selected.version == "v1" && selected.targetSelectionPolicyVersion == null &&
            selected.promptVersion == null && selected.outputMappingVersion == null
        val claimAnalysisConfigurationChanged = role == CLAIM_EXTRACTOR_ROLE && !legacyHeuristicSnapshot && (
            selected.configurationFingerprint != registration.configurationFingerprint ||
                selected.retentionDisclosure != registration.retentionDisclosure ||
                selected.targetSelectionPolicyVersion != registration.targetSelectionPolicyVersion ||
                selected.promptVersion != registration.promptVersion ||
                selected.outputMappingVersion != registration.outputMappingVersion
            )
        if (selected.version != registration.version || selected.model != registration.model ||
            selected.trustBoundary != registration.trustBoundary.id ||
            selected.dataCategories.toSet() != registration.dataCategories.map(DataCategory::id).toSet() ||
            scholarlyConfigurationChanged || openAccessConfigurationChanged || embeddingConfigurationChanged ||
            systemOneConfigurationChanged || claimAnalysisConfigurationChanged
        ) {
            throw ProviderCallRejectedException("Provider '$providerId' configuration or payload mapping changed after this Analysis Run was created.")
        }
        return registration
    }

    private fun requireConsent(
        role: String,
        registration: ProviderRegistration,
        requiredCategories: Set<DataCategory>,
        configuration: AnalysisConfigurationSnapshot,
    ) {
        val providerId = registration.providerId
        if (requiredCategories.isEmpty()) {
            throw ProviderCallRejectedException("External provider '$providerId' request has no classified payload categories.")
        }
        val consent = configuration.externalProviderConsents.firstOrNull { it.providerId == providerId }
            ?: throw ProviderCallRejectedException("Provider '$providerId' lacks per-run consent.")
        val consented = consent.dataCategories.mapNotNull(DataCategory::fromId).toSet()
        val missingConsent = requiredCategories - consented
        if (missingConsent.isNotEmpty()) {
            val categories = missingConsent.map { it.id }.sorted().joinToString(", ")
            throw ProviderCallRejectedException("Provider '$providerId' lacks per-run consent for: $categories.")
        }
        val snapshottedDisclosure = consent.retentionDisclosure
            ?: configuration.openAccessRetentionDisclosure.takeIf { role == OPEN_ACCESS_ROLE }
        if (snapshottedDisclosure != null && snapshottedDisclosure != registration.consentDisclosure()) {
            throw ProviderCallRejectedException("Provider '$providerId' retention disclosure changed after this Analysis Run was created.")
        }
    }
}
