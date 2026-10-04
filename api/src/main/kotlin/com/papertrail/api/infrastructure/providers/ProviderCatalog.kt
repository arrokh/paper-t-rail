package com.papertrail.api.infrastructure.providers

import com.fasterxml.jackson.databind.JsonNode
import com.papertrail.api.analysis.configuration.AnalysisConfigurationSnapshot
import com.papertrail.api.evidence.embedding.OllamaEmbeddingSettings
import com.papertrail.api.evidence.verification.provider.JevSystemOneSettings
import com.papertrail.api.evidence.verification.provider.LayaSystemOneSettings
import io.swagger.v3.oas.annotations.media.Schema
import java.security.MessageDigest

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

enum class ProviderTrustBoundary(val id: String) {
    LOCAL("LOCAL"),
    EXTERNAL("EXTERNAL"),
    UNREVIEWED("UNREVIEWED"),
}

enum class DataCategory(val id: String, val label: String, val description: String) {
    SOURCE_DOCUMENT_TEXT("source_document_text", "Source Document text", "Text extracted from the uploaded document."),
    BIBLIOGRAPHIC_METADATA("bibliographic_metadata", "Bibliographic metadata", "DOIs and the minimum title, author, year, or reference fields used for lookup."),
    CITATION_CONTEXT("citation_context", "Citation Context", "The citation-bearing clause or sentence submitted for claim extraction."),
    CITED_PAPER_CHUNKS("cited_paper_chunks", "Cited Paper chunks", "Text chunks from an acquired Cited Paper."),
    ATOMIC_CLAIMS("atomic_claims", "Atomic Claims", "Individual propositions submitted for assessment."),
    EVIDENCE_PASSAGES("evidence_passages", "Evidence Passages", "Passages from a Cited Paper submitted for assessment."),
    EMBEDDING_INPUT("embedding_input", "Embedding input", "Text submitted to a provider to calculate embeddings."),
    PROVIDER_CONTACT_EMAIL("provider_contact_email", "Provider contact email", "An operator contact email required or configured for a provider request."),
    CITED_PAPER_LOCATION("cited_paper_location", "Cited Paper location", "A discovered full-text URL used to request an openly licensed Cited Paper; Paper T-Rail does not send Source Document text.");

    companion object {
        fun fromId(id: String): DataCategory? = entries.firstOrNull { it.id == id }
    }
}

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

@Schema(description = "One enabled and classified provider option available for selection.")
data class ProviderOption(
    @field:Schema(description = "Provider role, such as `embedding` or `systemOne`.")
    val role: String,
    @field:Schema(description = "Stable provider identifier used in Analysis Run configuration.")
    val providerId: String,
    @field:Schema(description = "Human-readable provider name.")
    val displayName: String,
    @field:Schema(description = "Provider or model version used for provenance.")
    val version: String,
    @field:Schema(description = "Model identifier when this provider uses a model.")
    val model: String?,
    @field:Schema(description = "Provider trust boundary. Providers with an unclassified technical boundary are not listed.", allowableValues = ["LOCAL", "EXTERNAL"])
    val trustBoundary: String,
    @field:Schema(description = "Stable data-category IDs that may be sent to this provider.")
    val dataCategories: List<String>,
    @field:Schema(description = "Retention and deletion disclosure shown before per-run consent. Unknown terms are stated explicitly.")
    val retentionDisclosure: String?,
    @field:Schema(description = "Opaque fingerprint the server validates to ensure consent matches the disclosure returned by this directory.")
    val retentionDisclosureFingerprint: String?,
)

@Schema(description = "Stable data category and its user-facing disclosure.")
data class DataCategoryDisclosure(
    @field:Schema(description = "Stable identifier used in provider consent and configuration.")
    val id: String,
    @field:Schema(description = "Short display label.")
    val label: String,
    @field:Schema(description = "Description of the data represented by this category.")
    val description: String,
)

@Schema(description = "Selectable providers grouped by role and the catalog of data categories used for disclosure and consent.")
data class ProviderDirectoryResponse(
    @field:Schema(description = "Enabled provider options grouped by their role identifier.")
    val providers: Map<String, List<ProviderOption>>,
    @field:Schema(description = "Stable data-category identifiers and descriptions.")
    val dataCategories: List<DataCategoryDisclosure>,
)

class ProviderNotSelectableException(message: String) : IllegalArgumentException(message)

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

class ProviderCallRejectedException(message: String) : IllegalStateException(message)

/** Provider-bound content grouped by the stable category used to disclose and authorize it. */
class ProviderCallPayload(contentByCategory: Map<DataCategory, JsonNode>) {
    val contentByCategory: Map<DataCategory, JsonNode> = contentByCategory.mapValues { (_, content) -> content.deepCopy() }
    val dataCategories: Set<DataCategory> = this.contentByCategory.keys
}

/** The adapter serializes only this categorized payload after the gate approves it. */
class ProviderCallGate(private val catalog: ProviderCatalog) {
    fun <T> call(
        role: String,
        providerId: String,
        payload: ProviderCallPayload,
        configuration: AnalysisConfigurationSnapshot,
        sendRequest: (ProviderCallPayload) -> T,
    ): T {
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
        if (selected.version != registration.version || selected.model != registration.model ||
            selected.trustBoundary != registration.trustBoundary.id ||
            selected.dataCategories.toSet() != registration.dataCategories.map(DataCategory::id).toSet() ||
            scholarlyConfigurationChanged || openAccessConfigurationChanged || embeddingConfigurationChanged ||
            systemOneConfigurationChanged
        ) {
            throw ProviderCallRejectedException("Provider '$providerId' configuration or payload mapping changed after this Analysis Run was created.")
        }
        val actualPayloadCategories = payload.dataCategories
        if (actualPayloadCategories.any { it !in registration.dataCategories }) {
            throw ProviderCallRejectedException("Provider '$providerId' request contains an unclassified data category.")
        }
        if (registration.trustBoundary == ProviderTrustBoundary.EXTERNAL && actualPayloadCategories.isEmpty()) {
            throw ProviderCallRejectedException("External provider '$providerId' request has no classified payload categories.")
        }
        if (registration.trustBoundary == ProviderTrustBoundary.EXTERNAL) {
            val consent = configuration.externalProviderConsents.firstOrNull { it.providerId == providerId }
            val consented = consent?.dataCategories?.mapNotNull(DataCategory::fromId)?.toSet().orEmpty()
            val missingConsent = actualPayloadCategories - consented
            if (missingConsent.isNotEmpty()) {
                val categories = missingConsent.map { it.id }.sorted().joinToString(", ")
                throw ProviderCallRejectedException("Provider '$providerId' lacks per-run consent for: $categories.")
            }
            val snapshottedDisclosure = consent?.retentionDisclosure
                ?: configuration.openAccessRetentionDisclosure.takeIf { role == OPEN_ACCESS_ROLE }
            if (snapshottedDisclosure != null && snapshottedDisclosure != registration.consentDisclosure()) {
                throw ProviderCallRejectedException("Provider '$providerId' retention disclosure changed after this Analysis Run was created.")
            }
        }
        return sendRequest(payload)
    }
}
