package com.papertrail.api.providers

import com.papertrail.api.runs.AnalysisConfigurationSnapshot
import com.papertrail.api.runs.ExternalProviderConsentSnapshot
import com.papertrail.api.runs.ProviderSelection

const val CLAIM_EXTRACTOR_ROLE = "claimExtractor"
const val EMBEDDING_ROLE = "embedding"
const val SYSTEM_ONE_ROLE = "systemOne"

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
    PROVIDER_CONTACT_EMAIL("provider_contact_email", "Provider contact email", "An operator contact email required or configured for a provider request.");

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
    val enablementReviewed: Boolean = false,
)

data class ProviderOption(
    val role: String,
    val providerId: String,
    val displayName: String,
    val version: String,
    val model: String?,
    val trustBoundary: String,
    val dataCategories: List<String>,
    val retentionDisclosure: String?,
)

data class DataCategoryDisclosure(
    val id: String,
    val label: String,
    val description: String,
)

data class ProviderDirectoryResponse(
    val providers: List<ProviderOption>,
    val dataCategories: List<DataCategoryDisclosure>,
)

class ProviderNotSelectableException(message: String) : IllegalArgumentException(message)

class ProviderCatalog(registrations: Collection<ProviderRegistration>) {
    private val registrationsByRoleAndId: Map<Pair<String, String>, ProviderRegistration>

    init {
        require(registrations.map { it.role to it.providerId }.distinct().size == registrations.size) {
            "Provider registrations must be unique by role and provider ID."
        }
        require(registrations.all { it.role in setOf(CLAIM_EXTRACTOR_ROLE, EMBEDDING_ROLE, SYSTEM_ONE_ROLE) }) {
            "Provider registration contains an unsupported provider role."
        }
        require(registrations.none { it.enabled && it.trustBoundary == ProviderTrustBoundary.UNREVIEWED }) {
            "Unreviewed providers cannot be enabled."
        }
        require(registrations.none {
            it.enabled && it.trustBoundary == ProviderTrustBoundary.EXTERNAL &&
                (!it.enablementReviewed || it.retentionDisclosure.isNullOrBlank())
        }) {
            "External providers require a reviewed enablement decision and retention disclosure before they can be enabled."
        }
        require(registrations.filter { it.trustBoundary == ProviderTrustBoundary.EXTERNAL }.all { it.dataCategories.isNotEmpty() }) {
            "External providers must declare their data categories."
        }
        registrationsByRoleAndId = registrations.associateBy { it.role to it.providerId }
    }

    fun directory(): ProviderDirectoryResponse = ProviderDirectoryResponse(
        providers = registrationsByRoleAndId.values
            .filter { it.enabled }
            .sortedWith(compareBy(ProviderRegistration::role, ProviderRegistration::displayName))
            .map { registration ->
                ProviderOption(
                    role = registration.role,
                    providerId = registration.providerId,
                    displayName = registration.displayName,
                    version = registration.version,
                    model = registration.model,
                    trustBoundary = registration.trustBoundary.id,
                    dataCategories = registration.dataCategories.sortedBy { it.id }.map { it.id },
                    retentionDisclosure = registration.retentionDisclosure,
                )
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

    fun requireCallable(role: String, providerId: String): ProviderRegistration = requireSelectable(role, providerId)

    companion object {
        fun safeDefaults(): ProviderCatalog = ProviderCatalog(
            listOf(
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
                    displayName = "Local embeddings",
                    version = "v1",
                    model = "e5-small-v2",
                    trustBoundary = ProviderTrustBoundary.LOCAL,
                    enabled = true,
                    dataCategories = setOf(DataCategory.CITED_PAPER_CHUNKS, DataCategory.EMBEDDING_INPUT),
                ),
                ProviderRegistration(
                    role = EMBEDDING_ROLE,
                    providerId = "google-gemini-api",
                    displayName = "Google Gemini API embeddings",
                    version = "configured-model",
                    model = null,
                    trustBoundary = ProviderTrustBoundary.EXTERNAL,
                    enabled = false,
                    dataCategories = setOf(DataCategory.CITED_PAPER_CHUNKS, DataCategory.EMBEDDING_INPUT),
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
                    providerId = "jev",
                    displayName = "Jev hosted System One",
                    version = "configured-model",
                    model = null,
                    trustBoundary = ProviderTrustBoundary.EXTERNAL,
                    enabled = false,
                    dataCategories = setOf(DataCategory.ATOMIC_CLAIMS, DataCategory.EVIDENCE_PASSAGES),
                ),
            ),
        )
    }
}

class ProviderCallRejectedException(message: String) : IllegalStateException(message)

/** The adapter must supply categories derived from the request it is about to send. */
class ProviderCallGate(private val catalog: ProviderCatalog) {
    fun <T> call(
        role: String,
        providerId: String,
        actualPayloadCategories: Set<DataCategory>,
        configuration: AnalysisConfigurationSnapshot,
        sendRequest: () -> T,
    ): T {
        val registration = try {
            catalog.requireCallable(role, providerId)
        } catch (exception: ProviderNotSelectableException) {
            throw ProviderCallRejectedException(exception.message ?: "Provider is not selectable.")
        }
        val selected = when (role) {
            CLAIM_EXTRACTOR_ROLE -> configuration.claimExtractor
            EMBEDDING_ROLE -> configuration.embedding
            SYSTEM_ONE_ROLE -> configuration.systemOne
            else -> throw ProviderCallRejectedException("Provider role '$role' is not supported.")
        }
        if (selected.provider != providerId) {
            throw ProviderCallRejectedException("Provider '$providerId' was not selected for this Analysis Run.")
        }
        if (selected.version != registration.version || selected.model != registration.model ||
            selected.trustBoundary != registration.trustBoundary.id ||
            selected.dataCategories.toSet() != registration.dataCategories.map(DataCategory::id).toSet()
        ) {
            throw ProviderCallRejectedException("Provider '$providerId' configuration or payload mapping changed after this Analysis Run was created.")
        }
        if (actualPayloadCategories.any { it !in registration.dataCategories }) {
            throw ProviderCallRejectedException("Provider '$providerId' request contains an unclassified data category.")
        }
        if (registration.trustBoundary == ProviderTrustBoundary.EXTERNAL && actualPayloadCategories.isEmpty()) {
            throw ProviderCallRejectedException("External provider '$providerId' request has no classified payload categories.")
        }
        if (registration.trustBoundary == ProviderTrustBoundary.EXTERNAL) {
            val consented = configuration.externalProviderConsents
                .firstOrNull { it.providerId == providerId }
                ?.dataCategories
                ?.mapNotNull(DataCategory::fromId)
                ?.toSet()
                .orEmpty()
            val missingConsent = actualPayloadCategories - consented
            if (missingConsent.isNotEmpty()) {
                val categories = missingConsent.map { it.id }.sorted().joinToString(", ")
                throw ProviderCallRejectedException("Provider '$providerId' lacks per-run consent for: $categories.")
            }
        }
        return sendRequest()
    }
}
