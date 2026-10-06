package com.papertrail.api.external.openai

import com.papertrail.api.infrastructure.providers.ProviderTrustBoundary
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OpenAiCompatibleEndpointSettingsTest {
    @Test
    fun `shared endpoint fingerprints non-secret connection configuration`() {
        assertFalse(OpenAiCompatibleEndpointSettings.disabled().isSelectable)
        val settings = localSettings(apiKey = "super-secret-test-key")

        assertTrue(settings.isSelectable)
        assertEquals(ProviderTrustBoundary.LOCAL, settings.trustBoundary)
        assertNotNull(settings.configurationFingerprint)
        assertFalse(settings.configurationFingerprint.orEmpty().contains("super-secret-test-key"))
        assertFalse(settings.toString().contains("super-secret-test-key"))
        assertFalse(settings.toString().contains("127.0.0.1"))
        assertNotEquals(settings.configurationFingerprint, settings.copy(maxResponseBytes = 512).configurationFingerprint)
    }

    @Test
    fun `adds the OpenAI v1 API path when configured with a service root`() {
        val rootBase = localSettings(baseUrl = "http://127.0.0.1:1234")
        val versionedBase = localSettings(baseUrl = "http://127.0.0.1:1234/v1")

        assertEquals("http://127.0.0.1:1234/v1/chat/completions", rootBase.chatCompletionsUri.toString())
        assertEquals("http://127.0.0.1:1234/v1/models", rootBase.modelsUri.toString())
        assertEquals("http://127.0.0.1:1234/v1/chat/completions", versionedBase.chatCompletionsUri.toString())
        assertEquals("http://127.0.0.1:1234/v1/models", versionedBase.modelsUri.toString())
    }

    @Test
    fun `external endpoints require HTTPS but unknown retention terms do not block selection`() {
        val external = OpenAiCompatibleEndpointSettings(
            enabled = true,
            baseUrl = "https://api.example.test/v1",
            trustedHosts = setOf("localhost"),
        )

        assertEquals(ProviderTrustBoundary.EXTERNAL, external.trustBoundary)
        assertTrue(external.isSelectable)
        assertTrue(external.copy(
            retentionDisclosure = "Retention and deletion details are unknown; consult the provider's terms.",
        ).isSelectable)
        assertFalse(external.copy(
            baseUrl = "http://api.example.test/v1",
            retentionDisclosure = "Retention terms are disclosed.",
        ).isSelectable)
    }

    @Test
    fun `rejects unsafe endpoint syntax and invalid transport budgets`() {
        val invalidEndpoints = listOf(
            "https://user:password@api.example.test/v1",
            "https://api.example.test/v1?token=secret",
            "https://api.example.test/v1#fragment",
            "https://api.example.test/../admin",
        )
        invalidEndpoints.forEach { endpoint ->
            assertFalse(localSettings(baseUrl = endpoint).isConfigurationValid)
            assertEquals(null, localSettings(baseUrl = endpoint).chatCompletionsUri)
        }
        assertFalse(localSettings(maxRequestBytes = 0).isConfigurationValid)
        assertFalse(localSettings(maxResponseBytes = 0).isConfigurationValid)
        assertFalse(localSettings(requestTimeoutMillis = 0).isConfigurationValid)
        assertFalse(localSettings(apiKey = "x".repeat(OpenAiCompatibleEndpointSettings.MAX_API_KEY_LENGTH + 1)).isConfigurationValid)
        assertFalse(localSettings(baseUrl = "http://127.0.0.1/" + "a".repeat(OpenAiCompatibleEndpointSettings.MAX_BASE_URL_LENGTH)).isConfigurationValid)
        assertFalse(localSettings(baseUrl = " http://127.0.0.1:9090/v1").isConfigurationValid)
    }

    private fun localSettings(
        baseUrl: String = "http://127.0.0.1:9090/v1",
        apiKey: String? = null,
        maxRequestBytes: Int = 1_048_576,
        maxResponseBytes: Int = 1_048_576,
        requestTimeoutMillis: Long = 60_000,
    ) = OpenAiCompatibleEndpointSettings(
        enabled = true,
        baseUrl = baseUrl,
        apiKey = apiKey,
        trustedHosts = setOf("127.0.0.1"),
        maxRequestBytes = maxRequestBytes,
        maxResponseBytes = maxResponseBytes,
        requestTimeoutMillis = requestTimeoutMillis,
    )
}
