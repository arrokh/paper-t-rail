package com.papertrail.api.external.ollama

import com.papertrail.api.evidence.domain.EmbeddingProfile
import com.papertrail.api.infrastructure.crypto.sha256Hex
import com.papertrail.api.infrastructure.providers.ProviderTrustBoundary
import java.net.URI

/** Server-side Ollama settings; credentials and endpoint details never enter provider-directory responses. */
data class OllamaEmbeddingSettings(
    val enabled: Boolean,
    val baseUrl: String,
    val modelId: String,
    val dimension: Int,
    val apiKey: String? = null,
    val trustedHosts: Set<String> = emptySet(),
    val requestTimeoutMillis: Long = DEFAULT_REQUEST_TIMEOUT_MILLIS,
    val retentionDisclosure: String? = null,
    val providerId: String = EMBEDDINGGEMMA_PROVIDER_ID,
) {
    val baseUri: URI? = parseBaseUri(baseUrl)
    val endpointUri: URI? = baseUri?.let { URI("${it.toASCIIString().trimEnd('/')}/api/embed") }
    private val normalizedTrustedHosts = trustedHosts.map(::normalizeHost).toSet()
    val isConfigurationValid: Boolean = baseUri != null &&
        MODEL_ID_PATTERN.matches(modelId) &&
        dimension in 1..MAX_DIMENSION &&
        requestTimeoutMillis in 1..MAX_REQUEST_TIMEOUT_MILLIS &&
        apiKey.orEmpty().none(Char::isISOControl)
    val trustBoundary: ProviderTrustBoundary = baseUri?.host
        ?.let(::normalizeHost)
        ?.let { host ->
            if (host in normalizedTrustedHosts) ProviderTrustBoundary.LOCAL
            else ProviderTrustBoundary.EXTERNAL
        }
        ?: ProviderTrustBoundary.UNREVIEWED
    val configurationFingerprint: String? = baseUri
        ?.toASCIIString()
        ?.let { sha256Hex("paper-trail-ollama-endpoint-v1\n$it".toByteArray(Charsets.UTF_8)) }
    val isSelectable: Boolean = enabled && isConfigurationValid &&
        trustBoundary in setOf(ProviderTrustBoundary.LOCAL, ProviderTrustBoundary.EXTERNAL)
    val isSelectableForNewRuns: Boolean = isSelectable &&
        providerId == EMBEDDINGGEMMA_PROVIDER_ID && modelId == EMBEDDINGGEMMA_MODEL_ID

    init {
        require(trustedHosts.none(String::isBlank)) { "Ollama trusted hosts must not contain blank entries." }
    }

    override fun toString(): String =
        "OllamaEmbeddingSettings(enabled=$enabled, modelId=$modelId, dimension=$dimension, " +
            "trustBoundary=${trustBoundary.id}, apiKeyConfigured=${!apiKey.isNullOrBlank()})"

    private fun normalizeHost(host: String): String = host
        .removePrefix("[")
        .removeSuffix("]")
        .trimEnd('.')
        .lowercase()

    fun forLegacyNomicCompatibility(): OllamaEmbeddingSettings = copy(
        providerId = LEGACY_NOMIC_PROVIDER_ID,
        modelId = LEGACY_NOMIC_MODEL_ID,
        dimension = LEGACY_NOMIC_DIMENSION,
    )

    companion object {
        const val LEGACY_NOMIC_PROVIDER_ID = "ollama"
        const val EMBEDDINGGEMMA_PROVIDER_ID = "ollama-embeddinggemma-2"
        const val EMBEDDINGGEMMA_MODEL_ID = "embeddinggemma-2:270m"
        const val LEGACY_NOMIC_MODEL_ID = "nomic-embed-text:v1.5"
        const val LEGACY_NOMIC_DIMENSION = 768
        const val VERSION = "v1"
        const val DEFAULT_DIMENSION = 768
        const val DEFAULT_REQUEST_TIMEOUT_MILLIS = 60_000L
        const val MAX_REQUEST_TIMEOUT_MILLIS = 600_000L
        const val MAX_DIMENSION = EmbeddingProfile.MAX_VECTOR_DIMENSION
        const val MAX_RESPONSE_BYTES = 1_000_000

        private val MODEL_ID_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._:/-]{0,159}")

        fun disabled(): OllamaEmbeddingSettings = OllamaEmbeddingSettings(
            enabled = false,
            baseUrl = "",
            modelId = "",
            dimension = DEFAULT_DIMENSION,
            providerId = EMBEDDINGGEMMA_PROVIDER_ID,
        )

        private fun parseBaseUri(value: String): URI? = runCatching {
            val uri = URI(value.trim())
            if (uri.scheme?.lowercase() !in setOf("http", "https") ||
                uri.host.isNullOrBlank() || uri.userInfo != null || uri.query != null || uri.fragment != null ||
                uri.port == 0 || uri.port !in -1..65535
            ) {
                return null
            }
            uri
        }.getOrNull()
    }
}
