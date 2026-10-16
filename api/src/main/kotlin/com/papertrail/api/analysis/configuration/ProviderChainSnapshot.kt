package com.papertrail.api.analysis.configuration

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "Immutable, fixed-order provider-chain configuration pinned for reference resolution. The chain never accepts client-defined providers, ordering, thresholds, or operational limits.")
data class ProviderChainSnapshot(
    @field:Schema(description = "Versioned server-known chain identifier.", allowableValues = [CHAIN_ID])
    val chainId: String,
    @field:Schema(description = "Exact ordered provider registrations pinned for this chain: Crossref followed by DataCite.")
    val providers: List<ProviderSelection>,
    @field:Schema(description = "Version of the provider-response mapping used by this chain.")
    val mappingVersion: String,
    @field:Schema(description = "Conservative identity-resolution policy version pinned for this chain.")
    val policyVersion: String,
    @field:Schema(description = "Effective server-approved budgets and provider limits.")
    val limits: ProviderChainLimitsSnapshot,
) {
    init {
        require(chainId == CHAIN_ID) { "Unsupported reference-resolution provider chain." }
        require(providers.map(ProviderSelection::provider) == PROVIDER_IDS) {
            "The exact DOI chain must pin Crossref followed by DataCite."
        }
        require(mappingVersion == MAPPING_VERSION) { "Unsupported reference-resolution provider mapping." }
        require(policyVersion.isNotBlank()) { "Provider-chain policy version must be configured." }
        require(limits == ProviderChainLimitsSnapshot.EXACT_DOI_V1) { "Provider-chain limits must match the approved exact-DOI budget." }
    }

    companion object {
        const val CHAIN_ID = "crossref-datacite-exact-doi-v1"
        const val MAPPING_VERSION = "crossref-datacite-scholarly-metadata-v1"
        const val CROSSREF_PROVIDER_ID = "crossref"
        const val DATACITE_PROVIDER_ID = "datacite"
        val PROVIDER_IDS = listOf(CROSSREF_PROVIDER_ID, DATACITE_PROVIDER_ID)
    }
}
