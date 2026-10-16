package com.papertrail.api.infrastructure.providers.http

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "One server-defined provider chain. Its providers and order are fixed; each external provider requires independent per-run consent.")
data class ProviderChainOption(
    @field:Schema(description = "Stable server-issued chain identifier selected by the Analysis Run request.", allowableValues = ["crossref-datacite-exact-doi-v1"])
    val chainId: String,
    @field:Schema(description = "Human-readable name for this fixed provider chain.")
    val displayName: String,
    @field:Schema(description = "Chain behavior summary, including its exact-DOI-only DataCite lookup scope.")
    val description: String,
    @field:Schema(description = "Provider disclosures in the exact execution order. Each external provider must be consented to separately.")
    val providers: List<ProviderOption>,
)
