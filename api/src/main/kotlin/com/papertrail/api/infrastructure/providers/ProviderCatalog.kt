package com.papertrail.api.infrastructure.providers

import com.papertrail.api.analysis.configuration.AnalysisConfigurationSnapshot
import com.papertrail.api.external.ollama.OllamaEmbeddingSettings
import com.papertrail.api.external.jev.JevSystemOneSettings
import com.papertrail.api.external.laya.LayaSystemOneSettings
import com.papertrail.api.citation.claims.domain.ClaimAnalysisVersions
import com.papertrail.api.citation.claims.provider.OpenAiCompatibleClaimAnalysisSettings
import com.papertrail.api.external.openai.OpenAiCompatibleEndpointSettings
import java.security.MessageDigest
import com.papertrail.api.infrastructure.providers.http.DataCategoryDisclosure
import com.papertrail.api.infrastructure.providers.http.ProviderDirectoryResponse
import com.papertrail.api.infrastructure.providers.http.ProviderOption

const val CLAIM_EXTRACTOR_ROLE = "claimExtractor"
const val EMBEDDING_ROLE = "embedding"
const val SYSTEM_ONE_ROLE = "systemOne"
const val SCHOLARLY_METADATA_ROLE = "scholarlyMetadata"
const val OPEN_ACCESS_ROLE = "openAccess"

private const val UNKNOWN_RETENTION_DISCLOSURE =
    "Paper T-Rail has not verified this provider's data-retention or deletion terms. Retention and deletion details are unknown; consult the provider's terms."

private fun sha256Fingerprint(value: String): String = MessageDigest
    .getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

private val EMBEDDING_DATA_CATEGORIES = setOf(
    DataCategory.CITED_PAPER_CHUNKS,
    DataCategory.ATOMIC_CLAIMS,
    DataCategory.EMBEDDING_INPUT,
)

data class ProviderRegistration(
    val role: String,
    val providerId: String,
    val displayName: String,
    val version: String,
    val model: String?,
    val trustBoundary: ProviderTrustBoundary,
    val enabled: Boolean,
    val dataCategories: Set<DataCategory>,
    val retentionDisclosure: String? = null,
    val payloadConfigurationFingerprint: String? = null,
    val configurationFingerprint: String? = null,
    val embeddingDimension: Int? = null,
    val targetSelectionPolicyVersion: String? = null,
    val promptVersion: String? = null,
    val outputMappingVersion: String? = null,
) {
    fun consentDisclosure(): String? = if (trustBoundary == ProviderTrustBoundary.EXTERNAL) {
        retentionDisclosure?.takeIf(String::isNotBlank) ?: UNKNOWN_RETENTION_DISCLOSURE
    } else {
        retentionDisclosure
    }

    fun consentDisclosureFingerprint(): String? = consentDisclosure()?.let { disclosure ->
        sha256Fingerprint("paper-trail-provider-disclosure-v1\n$providerId\n$disclosure")
    }
}

class ProviderCatalog(registrations: Collection<ProviderRegistration>) {
    private val registrationsByRoleAndId: Map<Pair<String, String>, ProviderRegistration>

    init {
        require(registrations.map { it.role to it.providerId }.distinct().size == registrations.size) {
            "Provider registrations must be unique by role and provider ID."
        }
        require(registrations.all { it.role in setOf(CLAIM_EXTRACTOR_ROLE, EMBEDDING_ROLE, SYSTEM_ONE_ROLE, SCHOLARLY_METADATA_ROLE, OPEN_ACCESS_ROLE) }) {
            "Provider registration contains an unsupported provider role."
        }
        require(registrations.all { it.embeddingDimension == null || it.embeddingDimension > 0 }) {
            "Configured embedding dimensions must be positive."
        }
        require(registrations.none { it.enabled && it.trustBoundary == ProviderTrustBoundary.UNREVIEWED }) {
            "Technically unclassified providers cannot be enabled."
        }
        require(registrations.filter { it.trustBoundary == ProviderTrustBoundary.EXTERNAL }.all { it.dataCategories.isNotEmpty() }) {
            "External providers must declare their data categories."
        }
        registrationsByRoleAndId = registrations.associateBy { it.role to it.providerId }
    }

    fun directory(): ProviderDirectoryResponse = ProviderDirectoryResponse(
        providers = registrationsByRoleAndId.values
            .filter(ProviderRegistration::enabled)
            .groupBy(ProviderRegistration::role)
            .toSortedMap()
            .mapValues { (_, registrations) ->
                registrations.sortedBy(ProviderRegistration::displayName).map { registration ->
                    ProviderOption(
                        role = registration.role,
                        providerId = registration.providerId,
                        displayName = registration.displayName,
                        version = registration.version,
                        model = registration.model,
                        trustBoundary = registration.trustBoundary.id,
                        dataCategories = registration.dataCategories.sortedBy { it.id }.map { it.id },
                        retentionDisclosure = registration.consentDisclosure(),
                        retentionDisclosureFingerprint = registration.consentDisclosureFingerprint(),
                    )
                }
            },
        dataCategories = DataCategory.entries.map { DataCategoryDisclosure(it.id, it.label, it.description) },
    )

    fun requireSelectable(role: String, providerId: String): ProviderRegistration {
        val registration = registrationsByRoleAndId[role to providerId]
            ?: throw ProviderNotSelectableException("Provider '$providerId' is unknown or unclassified for role '$role'.")
        if (registration.trustBoundary == ProviderTrustBoundary.UNREVIEWED) {
            throw ProviderNotSelectableException("Provider '$providerId' is unclassified and cannot be selected.")
        }
        if (!registration.enabled) {
            throw ProviderNotSelectableException("Provider '$providerId' is disabled and cannot be selected.")
        }
        return registration
    }

    companion object {
        fun safeDefaults(
            crossrefEnabled: Boolean = false,
            crossrefRetentionDisclosure: String? = null,
            crossrefContactEmail: String? = null,
            unpaywallEnabled: Boolean = false,
            unpaywallRetentionDisclosure: String? = null,
            unpaywallContactEmail: String? = null,
            ollamaEmbeddingSettings: OllamaEmbeddingSettings = OllamaEmbeddingSettings.disabled(),
            layaSystemOneSettings: LayaSystemOneSettings = LayaSystemOneSettings.disabled(),
            openAiCompatibleClaimAnalysisSettings: OpenAiCompatibleClaimAnalysisSettings = OpenAiCompatibleClaimAnalysisSettings.disabled(),
            jevSystemOneSettings: JevSystemOneSettings = JevSystemOneSettings.disabled(),
        ): ProviderCatalog {
            require(!unpaywallEnabled || !unpaywallContactEmail.isNullOrBlank()) {
                "Unpaywall requires a configured provider contact email before it can be enabled."
            }
            return ProviderCatalog(
                listOf(
                ProviderRegistration(
                    role = OPEN_ACCESS_ROLE,
                    providerId = "recorded-fixtures",
                    displayName = "Recorded open-access fixtures",
                    version = "v1",
                    model = null,
                    trustBoundary = ProviderTrustBoundary.LOCAL,
                    enabled = true,
                    dataCategories = setOf(DataCategory.BIBLIOGRAPHIC_METADATA, DataCategory.CITED_PAPER_LOCATION),
                ),
                ProviderRegistration(
                    role = OPEN_ACCESS_ROLE,
                    providerId = "unpaywall",
                    displayName = "Unpaywall and discovered open-access hosts",
                    version = "v2",
                    model = null,
                    trustBoundary = ProviderTrustBoundary.EXTERNAL,
                    enabled = unpaywallEnabled,
                    dataCategories = setOfNotNull(
                        DataCategory.BIBLIOGRAPHIC_METADATA,
                        DataCategory.CITED_PAPER_LOCATION,
                        DataCategory.PROVIDER_CONTACT_EMAIL.takeIf { !unpaywallContactEmail.isNullOrBlank() },
                    ),
                    retentionDisclosure = unpaywallRetentionDisclosure,
                    payloadConfigurationFingerprint = unpaywallContactEmail
                        ?.takeIf(String::isNotBlank)
                        ?.let(::sha256Fingerprint),
                ),
                ProviderRegistration(
                    role = SCHOLARLY_METADATA_ROLE,
                    providerId = "recorded-fixtures",
                    displayName = "Recorded scholarly metadata fixtures",
                    version = "v1",
                    model = null,
                    trustBoundary = ProviderTrustBoundary.LOCAL,
                    enabled = true,
                    dataCategories = setOf(DataCategory.BIBLIOGRAPHIC_METADATA),
                ),
                ProviderRegistration(
                    role = SCHOLARLY_METADATA_ROLE,
                    providerId = "crossref",
                    displayName = "Crossref REST API",
                    version = "v1",
                    model = null,
                    trustBoundary = ProviderTrustBoundary.EXTERNAL,
                    enabled = crossrefEnabled,
                    dataCategories = setOfNotNull(
                        DataCategory.BIBLIOGRAPHIC_METADATA,
                        DataCategory.PROVIDER_CONTACT_EMAIL.takeIf { !crossrefContactEmail.isNullOrBlank() },
                    ),
                    retentionDisclosure = crossrefRetentionDisclosure,
                    payloadConfigurationFingerprint = crossrefContactEmail
                        ?.takeIf(String::isNotBlank)
                        ?.let(::sha256Fingerprint),
                ),
                ProviderRegistration(
                    role = CLAIM_EXTRACTOR_ROLE,
                    providerId = "heuristic",
                    displayName = "Heuristic claim extraction",
                    version = "v1",
                    model = null,
                    trustBoundary = ProviderTrustBoundary.LOCAL,
                    enabled = true,
                    dataCategories = setOf(DataCategory.CITATION_CONTEXT),
                    targetSelectionPolicyVersion = ClaimAnalysisVersions.HEURISTIC_TARGET_SELECTION_POLICY,
                    outputMappingVersion = ClaimAnalysisVersions.HEURISTIC_OUTPUT_MAPPING,
                ),
                ProviderRegistration(
                    role = CLAIM_EXTRACTOR_ROLE,
                    providerId = "google-gemini-api",
                    displayName = "Google Gemini API",
                    version = "configured-model",
                    model = null,
                    trustBoundary = ProviderTrustBoundary.EXTERNAL,
                    enabled = false,
                    dataCategories = setOf(DataCategory.CITATION_CONTEXT),
                ),
                ProviderRegistration(
                    role = CLAIM_EXTRACTOR_ROLE,
                    providerId = OpenAiCompatibleEndpointSettings.PROVIDER_ID,
                    displayName = "OpenAI-compatible Chat Completions",
                    version = OpenAiCompatibleEndpointSettings.VERSION,
                    model = openAiCompatibleClaimAnalysisSettings.modelId.takeIf(String::isNotBlank),
                    trustBoundary = openAiCompatibleClaimAnalysisSettings.trustBoundary,
                    enabled = openAiCompatibleClaimAnalysisSettings.isSelectable,
                    dataCategories = setOf(DataCategory.CITATION_CONTEXT, DataCategory.BIBLIOGRAPHIC_METADATA),
                    retentionDisclosure = openAiCompatibleClaimAnalysisSettings.retentionDisclosure,
                    configurationFingerprint = openAiCompatibleClaimAnalysisSettings.configurationFingerprint,
                    targetSelectionPolicyVersion = ClaimAnalysisVersions.MODEL_TARGET_SELECTION_POLICY,
                    promptVersion = ClaimAnalysisVersions.OPENAI_COMPATIBLE_PROMPT,
                    outputMappingVersion = ClaimAnalysisVersions.OPENAI_COMPATIBLE_OUTPUT_MAPPING,
                ),
                ProviderRegistration(
                    role = EMBEDDING_ROLE,
                    providerId = "local",
                    displayName = "Local feature-hash embeddings",
                    version = "v1",
                    model = "feature-hash-384-v1",
                    trustBoundary = ProviderTrustBoundary.LOCAL,
                    enabled = true,
                    dataCategories = EMBEDDING_DATA_CATEGORIES,
                    embeddingDimension = 384,
                ),
                ProviderRegistration(
                    role = EMBEDDING_ROLE,
                    providerId = "google-gemini-api",
                    displayName = "Google Gemini API embeddings",
                    version = "configured-model",
                    model = null,
                    trustBoundary = ProviderTrustBoundary.EXTERNAL,
                    enabled = false,
                    dataCategories = EMBEDDING_DATA_CATEGORIES,
                ),
                ProviderRegistration(
                    role = EMBEDDING_ROLE,
                    providerId = OllamaEmbeddingSettings.PROVIDER_ID,
                    displayName = ollamaEmbeddingSettings.modelId.takeIf(String::isNotBlank)
                        ?.let { "Ollama embeddings ($it)" } ?: "Ollama embeddings",
                    version = OllamaEmbeddingSettings.VERSION,
                    model = ollamaEmbeddingSettings.modelId.takeIf(String::isNotBlank),
                    trustBoundary = ollamaEmbeddingSettings.trustBoundary,
                    enabled = ollamaEmbeddingSettings.isSelectable,
                    dataCategories = EMBEDDING_DATA_CATEGORIES,
                    retentionDisclosure = ollamaEmbeddingSettings.retentionDisclosure,
                    configurationFingerprint = ollamaEmbeddingSettings.configurationFingerprint
                        .takeIf { ollamaEmbeddingSettings.isConfigurationValid },
                    embeddingDimension = ollamaEmbeddingSettings.dimension
                        .takeIf { ollamaEmbeddingSettings.isConfigurationValid },
                ),
                ProviderRegistration(
                    role = SYSTEM_ONE_ROLE,
                    providerId = "mock",
                    displayName = "Mock verifier",
                    version = "v1",
                    model = "mock-v1",
                    trustBoundary = ProviderTrustBoundary.LOCAL,
                    enabled = true,
                    dataCategories = setOf(DataCategory.ATOMIC_CLAIMS, DataCategory.EVIDENCE_PASSAGES),
                ),
                ProviderRegistration(
                    role = SYSTEM_ONE_ROLE,
                    providerId = LayaSystemOneSettings.PROVIDER_ID,
                    displayName = "Laya System One (local evaluation)",
                    version = LayaSystemOneSettings.PROVIDER_VERSION,
                    model = LayaSystemOneSettings.PINNED_MODEL_ID,
                    trustBoundary = layaSystemOneSettings.trustBoundary,
                    enabled = layaSystemOneSettings.isSelectable,
                    dataCategories = setOf(DataCategory.ATOMIC_CLAIMS, DataCategory.EVIDENCE_PASSAGES),
                    configurationFingerprint = layaSystemOneSettings.configurationFingerprint,
                ),
                ProviderRegistration(
                    role = SYSTEM_ONE_ROLE,
                    providerId = "jev",
                    displayName = "Jev hosted System One",
                    version = JevSystemOneSettings.PROVIDER_VERSION,
                    model = jevSystemOneSettings.modelId,
                    trustBoundary = ProviderTrustBoundary.EXTERNAL,
                    enabled = jevSystemOneSettings.isSelectable,
                    dataCategories = setOf(DataCategory.ATOMIC_CLAIMS, DataCategory.EVIDENCE_PASSAGES),
                    retentionDisclosure = jevSystemOneSettings.retentionDisclosure,
                    configurationFingerprint = jevSystemOneSettings.configurationFingerprint,
                ),
                ),
            )
        }
    }
}
