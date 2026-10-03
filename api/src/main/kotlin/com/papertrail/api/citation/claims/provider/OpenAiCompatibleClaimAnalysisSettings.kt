package com.papertrail.api.citation.claims.provider

import com.papertrail.api.citation.claims.domain.ClaimAnalysisVersions
import com.papertrail.api.infrastructure.crypto.sha256Hex
import com.papertrail.api.infrastructure.providers.ProviderTrustBoundary
import com.papertrail.api.infrastructure.providers.openai.OpenAiCompatibleEndpointSettings

/** Claim-analysis model profile layered on the shared OpenAI-compatible endpoint configuration. */
data class OpenAiCompatibleClaimAnalysisSettings(
    val endpoint: OpenAiCompatibleEndpointSettings,
    val modelId: String,
    val contextWindowTokens: Int = DEFAULT_CONTEXT_WINDOW_TOKENS,
    val maxCompletionTokens: Int = DEFAULT_MAX_COMPLETION_TOKENS,
) {
    val isConfigurationValid: Boolean = endpoint.isConfigurationValid &&
        MODEL_ID_PATTERN.matches(modelId) &&
        contextWindowTokens in 2..MAX_CONTEXT_WINDOW_TOKENS &&
        maxCompletionTokens in 1 until contextWindowTokens
    val isSelectable: Boolean = endpoint.isSelectable && isConfigurationValid
    val trustBoundary: ProviderTrustBoundary = endpoint.trustBoundary
    val enablementReviewed: Boolean = endpoint.enablementReviewed
    val retentionDisclosure: String? = endpoint.retentionDisclosure
    // Keep this v1 fingerprint stable so queued Analysis Runs created before the shared-transport refactor remain processable.
    val configurationFingerprint: String? = endpoint.baseUri?.toASCIIString()?.let { endpointUri ->
        sha256Hex(
            listOf(
                "paper-trail-openai-compatible-claim-analysis-v1",
                endpointUri,
                modelId,
                contextWindowTokens,
                maxCompletionTokens,
                endpoint.maxRequestBytes,
                endpoint.maxResponseBytes,
                endpoint.requestTimeoutMillis,
                !endpoint.apiKey.isNullOrBlank(),
                ClaimAnalysisVersions.MODEL_TARGET_SELECTION_POLICY,
                ClaimAnalysisVersions.OPENAI_COMPATIBLE_PROMPT,
                ClaimAnalysisVersions.OPENAI_COMPATIBLE_OUTPUT_MAPPING,
            ).joinToString("\n").toByteArray(Charsets.UTF_8),
        )
    }

    override fun toString(): String =
        "OpenAiCompatibleClaimAnalysisSettings(modelId=$modelId, contextWindowTokens=$contextWindowTokens, " +
            "trustBoundary=${trustBoundary.id})"

    companion object {
        const val PROVIDER_ID = OpenAiCompatibleEndpointSettings.PROVIDER_ID
        const val VERSION = OpenAiCompatibleEndpointSettings.VERSION
        const val DEFAULT_CONTEXT_WINDOW_TOKENS = 32_768
        const val DEFAULT_MAX_COMPLETION_TOKENS = 2_048
        const val MAX_CONTEXT_WINDOW_TOKENS = 2_000_000

        private val MODEL_ID_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._:/-]{0,159}")

        fun disabled(): OpenAiCompatibleClaimAnalysisSettings = OpenAiCompatibleClaimAnalysisSettings(
            endpoint = OpenAiCompatibleEndpointSettings.disabled(),
            modelId = "",
        )
    }
}
