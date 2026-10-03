package com.papertrail.api.infrastructure.providers

import com.papertrail.api.analysis.http.ExternalProviderConsentRequest

fun configuredExternalProviderCatalog(): ProviderCatalog = ProviderCatalog(
    listOf(
        ProviderRegistration(
            OPEN_ACCESS_ROLE,
            "recorded-fixtures",
            "Recorded open-access fixtures",
            "v1",
            null,
            ProviderTrustBoundary.LOCAL,
            true,
            setOf(DataCategory.BIBLIOGRAPHIC_METADATA, DataCategory.CITED_PAPER_LOCATION),
        ),
        ProviderRegistration(
            SCHOLARLY_METADATA_ROLE,
            "recorded-fixtures",
            "Recorded metadata fixtures",
            "v1",
            null,
            ProviderTrustBoundary.LOCAL,
            true,
            setOf(DataCategory.BIBLIOGRAPHIC_METADATA),
        ),
        ProviderRegistration(
            SCHOLARLY_METADATA_ROLE,
            "crossref",
            "Crossref",
            "v1",
            null,
            ProviderTrustBoundary.EXTERNAL,
            true,
            setOf(DataCategory.BIBLIOGRAPHIC_METADATA),
            retentionDisclosure = "Crossref retention disclosure for this controlled test deployment.",
        ),
        ProviderRegistration(
            CLAIM_EXTRACTOR_ROLE,
            "heuristic",
            "Heuristic",
            "v1",
            null,
            ProviderTrustBoundary.LOCAL,
            true,
            setOf(DataCategory.CITATION_CONTEXT),
        ),
        ProviderRegistration(
            CLAIM_EXTRACTOR_ROLE,
            "configured-llm",
            "Configured LLM",
            "v1",
            "model-1",
            ProviderTrustBoundary.EXTERNAL,
            true,
            setOf(DataCategory.CITATION_CONTEXT),
            retentionDisclosure = "Retention and deletion terms for this controlled test provider.",
        ),
        ProviderRegistration(
            EMBEDDING_ROLE,
            "local",
            "Local feature-hash embeddings",
            "v1",
            "feature-hash-384-v1",
            ProviderTrustBoundary.LOCAL,
            true,
            setOf(DataCategory.CITED_PAPER_CHUNKS, DataCategory.ATOMIC_CLAIMS, DataCategory.EMBEDDING_INPUT),
        ),
        ProviderRegistration(
            SYSTEM_ONE_ROLE,
            "mock",
            "Mock",
            "v1",
            "mock-v1",
            ProviderTrustBoundary.LOCAL,
            true,
            setOf(DataCategory.ATOMIC_CLAIMS, DataCategory.EVIDENCE_PASSAGES),
        ),
        ProviderRegistration(
            SYSTEM_ONE_ROLE,
            "unclassified-provider",
            "Unclassified provider fixture",
            "unknown",
            null,
            ProviderTrustBoundary.UNREVIEWED,
            false,
            setOf(DataCategory.ATOMIC_CLAIMS, DataCategory.EVIDENCE_PASSAGES),
        ),
    ),
)

fun externalProviderConsent(
    catalog: ProviderCatalog,
    providerId: String,
    dataCategories: List<String>,
): ExternalProviderConsentRequest {
    val provider = catalog.directory().providers.values.flatten().single { it.providerId == providerId }
    return ExternalProviderConsentRequest(
        providerId = providerId,
        dataCategories = dataCategories,
        retentionDisclosureFingerprint = requireNotNull(provider.retentionDisclosureFingerprint),
    )
}
