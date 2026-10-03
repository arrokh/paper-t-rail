package com.papertrail.api.citation.claims.provider

import com.papertrail.api.infrastructure.providers.ProviderTrustBoundary
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OpenAiCompatibleClaimAnalysisSettingsTest {
    @Test
    fun `provider is opt in and fingerprints non-secret endpoint configuration`() {
        assertFalse(OpenAiCompatibleClaimAnalysisSettings.disabled().isSelectable)
        val settings = localSettings(apiKey = "super-secret-test-key")

        assertTrue(settings.isSelectable)
        assertEquals(ProviderTrustBoundary.LOCAL, settings.trustBoundary)
        assertEquals("http://127.0.0.1:9090/v1/chat/completions", settings.endpointUri.toString())
        assertNotNull(settings.configurationFingerprint)
        assertFalse(settings.configurationFingerprint.orEmpty().contains("super-secret-test-key"))
        assertFalse(settings.toString().contains("super-secret-test-key"))
        assertFalse(settings.toString().contains("127.0.0.1"))
        assertNotEquals(settings.configurationFingerprint, settings.copy(modelId = "another-model").configurationFingerprint)
    }

    @Test
    fun `external hosts require HTTPS deployment review and a retention disclosure`() {
        val external = OpenAiCompatibleClaimAnalysisSettings(
            enabled = true,
            baseUrl = "https://api.example.test/v1",
            modelId = "reviewed-model",
            trustedHosts = setOf("localhost"),
        )

        assertEquals(ProviderTrustBoundary.EXTERNAL, external.trustBoundary)
        assertFalse(external.isSelectable)
        assertFalse(external.copy(enablementReviewed = true).isSelectable)
        assertTrue(external.copy(
            enablementReviewed = true,
            retentionDisclosure = "The deployment has reviewed the provider retention terms.",
        ).isSelectable)
        assertFalse(external.copy(
            baseUrl = "http://api.example.test/v1",
            enablementReviewed = true,
            retentionDisclosure = "Reviewed terms.",
        ).isSelectable)
    }

    @Test
    fun `rejects unsafe endpoint syntax and invalid request budgets`() {
        val invalidEndpoints = listOf(
            "https://user:password@api.example.test/v1",
            "https://api.example.test/v1?token=secret",
            "https://api.example.test/v1#fragment",
            "https://api.example.test/../admin",
        )
        invalidEndpoints.forEach { endpoint ->
            assertFalse(localSettings(baseUrl = endpoint).isConfigurationValid)
            assertNull(localSettings(baseUrl = endpoint).endpointUri)
        }
        assertFalse(localSettings(contextWindowTokens = 100, maxCompletionTokens = 100).isConfigurationValid)
        assertFalse(localSettings(maxRequestBytes = 0).isConfigurationValid)
        assertFalse(localSettings(requestTimeoutMillis = 0).isConfigurationValid)
        assertFalse(localSettings(apiKey = "x".repeat(OpenAiCompatibleClaimAnalysisSettings.MAX_API_KEY_LENGTH + 1)).isConfigurationValid)
        assertFalse(localSettings(baseUrl = "http://127.0.0.1/" + "a".repeat(OpenAiCompatibleClaimAnalysisSettings.MAX_BASE_URL_LENGTH)).isConfigurationValid)
        assertFalse(localSettings(baseUrl = " http://127.0.0.1:9090/v1").isConfigurationValid)
    }

    private fun localSettings(
        baseUrl: String = "http://127.0.0.1:9090/v1",
        modelId: String = "fixture-chat-model",
        apiKey: String? = null,
        contextWindowTokens: Int = 16_384,
        maxCompletionTokens: Int = 1_024,
        maxRequestBytes: Int = 1_048_576,
        requestTimeoutMillis: Long = 60_000,
    ) = OpenAiCompatibleClaimAnalysisSettings(
        enabled = true,
        baseUrl = baseUrl,
        modelId = modelId,
        apiKey = apiKey,
        trustedHosts = setOf("127.0.0.1"),
        contextWindowTokens = contextWindowTokens,
        maxCompletionTokens = maxCompletionTokens,
        maxRequestBytes = maxRequestBytes,
        requestTimeoutMillis = requestTimeoutMillis,
    )
}
