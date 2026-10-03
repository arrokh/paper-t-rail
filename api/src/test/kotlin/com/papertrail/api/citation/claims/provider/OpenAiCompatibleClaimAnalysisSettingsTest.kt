package com.papertrail.api.citation.claims.provider

import com.papertrail.api.infrastructure.providers.ProviderTrustBoundary
import com.papertrail.api.infrastructure.providers.openai.OpenAiCompatibleEndpointSettings
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OpenAiCompatibleClaimAnalysisSettingsTest {
    @Test
    fun `claim analysis profile uses shared endpoint settings and fingerprints its model contract`() {
        val endpoint = localEndpoint(apiKey = "super-secret-test-key")
        val settings = localSettings(endpoint = endpoint)

        assertTrue(settings.isSelectable)
        assertEquals(ProviderTrustBoundary.LOCAL, settings.trustBoundary)
        assertEquals("http://127.0.0.1:9090/v1/chat/completions", endpoint.chatCompletionsUri.toString())
        assertNotNull(settings.configurationFingerprint)
        assertFalse(settings.configurationFingerprint.orEmpty().contains("super-secret-test-key"))
        assertFalse(settings.toString().contains("super-secret-test-key"))
        assertFalse(settings.toString().contains("127.0.0.1"))
        assertNotEquals(settings.configurationFingerprint, settings.copy(modelId = "another-model").configurationFingerprint)
    }

    @Test
    fun `preserves the existing claim-analysis fingerprint for unchanged queued runs`() {
        val settings = localSettings()
        assertEquals(
            "bed04a4a0a75660bc70b4de5ca7e3528720219bb66b84807b39dc3e1973cb693",
            settings.configurationFingerprint,
        )
    }

    @Test
    fun `model selection belongs to the use-case profile rather than the shared endpoint`() {
        val endpoint = localEndpoint()
        val gemmaProfile = localSettings(endpoint = endpoint, modelId = "google/gemma-4-e2b")

        assertTrue(gemmaProfile.isSelectable)
        assertEquals(endpoint, gemmaProfile.endpoint)
        assertEquals("google/gemma-4-e2b", gemmaProfile.modelId)
    }

    @Test
    fun `claim-analysis profile rejects invalid model and context budgets`() {
        assertFalse(localSettings(modelId = " ").isConfigurationValid)
        assertFalse(localSettings(contextWindowTokens = 100, maxCompletionTokens = 100).isConfigurationValid)
        assertFalse(localSettings(contextWindowTokens = OpenAiCompatibleClaimAnalysisSettings.MAX_CONTEXT_WINDOW_TOKENS + 1).isConfigurationValid)
        assertFalse(OpenAiCompatibleClaimAnalysisSettings.disabled().isSelectable)
    }

    private fun localEndpoint(apiKey: String? = null) = OpenAiCompatibleEndpointSettings(
        enabled = true,
        baseUrl = "http://127.0.0.1:9090/v1",
        apiKey = apiKey,
        trustedHosts = setOf("127.0.0.1"),
    )

    private fun localSettings(
        endpoint: OpenAiCompatibleEndpointSettings = localEndpoint(),
        modelId: String = "fixture-chat-model",
        contextWindowTokens: Int = 16_384,
        maxCompletionTokens: Int = 1_024,
    ) = OpenAiCompatibleClaimAnalysisSettings(
        endpoint = endpoint,
        modelId = modelId,
        contextWindowTokens = contextWindowTokens,
        maxCompletionTokens = maxCompletionTokens,
    )
}
