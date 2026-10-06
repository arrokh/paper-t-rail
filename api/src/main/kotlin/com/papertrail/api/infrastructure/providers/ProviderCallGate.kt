package com.papertrail.api.infrastructure.providers

import com.papertrail.api.analysis.configuration.AnalysisConfigurationSnapshot

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

    private fun requireMatchingSelection(
        role: String,
        providerId: String,
        configuration: AnalysisConfigurationSnapshot,
    ): ProviderRegistration {
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
