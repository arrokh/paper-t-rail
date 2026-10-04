package com.papertrail.api.analysis.http

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "Provider selections and explicit per-run external-provider consent. Consent must identify the exact data categories and the server-issued fingerprint for the disclosure displayed to the user; the server resolves and snapshots disclosure text.")
data class RunConfigurationRequest(
    @field:Schema(
        description = "Claim-analysis provider. If omitted, use the deployment-configured default; heuristic remains selectable.",
        defaultValue = "openai-compatible-chat",
        requiredMode = Schema.RequiredMode.NOT_REQUIRED,
    )
    val claimExtractorProvider: String? = null,
    @field:Schema(
        description = "Embedding provider. If omitted, select local Ollama when available; otherwise use local feature-hash embeddings.",
        defaultValue = "ollama",
        requiredMode = Schema.RequiredMode.NOT_REQUIRED,
    )
    val embeddingProvider: String? = null,
    @field:Schema(description = "System One verification provider. If omitted, use the deployment default (mock if its default Laya is unavailable); an explicitly unavailable selection is rejected.", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    val systemOneProvider: String? = null,
    @field:Schema(description = "Scholarly metadata provider used for conservative bibliography resolution.", defaultValue = "recorded-fixtures", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    val scholarlyMetadataProvider: String = "recorded-fixtures",
    @field:Schema(description = "Provider used to discover and acquire legal cited full text.", defaultValue = "recorded-fixtures", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    val openAccessProvider: String = "recorded-fixtures",
    @field:Schema(description = "Client confirmations of the server-issued disclosure fingerprints and exact provider data categories approved for this run. Disclosure text is resolved and snapshotted by the server.", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    val externalProviderConsents: List<ExternalProviderConsentRequest> = emptyList(),
)
