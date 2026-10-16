package com.papertrail.api.scholarly.references.client

/** Chain-only lookup seam; `beforeWire` runs after provider authorization and immediately before HTTP. */
interface ProviderChainMetadataLookup {
    fun byDoiForChain(doi: String, beforeWire: () -> Unit): ProviderChainMetadataResponse<ScholarlyWork?>

    /** DataCite returns no results here and must never search by title. */
    fun searchByTitleForChain(
        title: String,
        candidateLimit: Int,
        beforeWire: () -> Unit,
    ): ProviderChainMetadataResponse<List<ScholarlyWork>>
}
