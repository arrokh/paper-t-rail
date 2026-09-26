package com.papertrail.api.analysis.http

import com.papertrail.api.analysis.configuration.ExternalProviderConsentSnapshot
import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "Provider selections and explicit per-run external-provider consent.")
data class RunConfigurationRequest(
    @field:Schema(description = "Claim extractor provider.", defaultValue = "heuristic", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    val claimExtractorProvider: String = "heuristic",
    @field:Schema(description = "Embedding provider.", defaultValue = "local", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    val embeddingProvider: String = "local",
    @field:Schema(description = "System One verification provider.", defaultValue = "mock", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    val systemOneProvider: String = "mock",
    @field:Schema(description = "Scholarly metadata provider used for conservative bibliography resolution.", defaultValue = "recorded-fixtures", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    val scholarlyMetadataProvider: String = "recorded-fixtures",
    @field:Schema(description = "Provider used to discover and acquire legal cited full text.", defaultValue = "recorded-fixtures", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    val openAccessProvider: String = "recorded-fixtures",
    @field:Schema(description = "Provider-specific data categories explicitly approved for this run.", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    val externalProviderConsents: List<ExternalProviderConsentSnapshot> = emptyList(),
)
