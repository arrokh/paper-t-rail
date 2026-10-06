package com.papertrail.api.infrastructure.providers.http

import io.swagger.v3.oas.annotations.media.Schema

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
