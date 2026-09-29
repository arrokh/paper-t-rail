package com.papertrail.api.evidence.verification.provider

import com.papertrail.api.infrastructure.crypto.sha256Hex
import com.papertrail.api.infrastructure.providers.ProviderTrustBoundary
import java.net.URI

/** API-side settings for the private, authenticated Laya evaluation sidecar. */
data class LayaSystemOneSettings(
    val enabled: Boolean,
    val baseUrl: String,
    val apiKey: String? = null,
    val trustedHosts: Set<String> = emptySet(),
    val requestTimeoutMillis: Long = DEFAULT_REQUEST_TIMEOUT_MILLIS,
) {
    val endpointUri: URI? = parseBaseUri(baseUrl)?.let { base ->
        URI("${base.toASCIIString().trimEnd('/')}/v1/systemone")
    }
    private val normalizedTrustedHosts = trustedHosts.map(::normalizeHost).toSet()
    val trustBoundary: ProviderTrustBoundary = parseBaseUri(baseUrl)
        ?.host
        ?.let(::normalizeHost)
        ?.let { host -> if (host in normalizedTrustedHosts) ProviderTrustBoundary.LOCAL else ProviderTrustBoundary.UNREVIEWED }
        ?: ProviderTrustBoundary.UNREVIEWED
    val configurationFingerprint: String? = endpointUri?.toASCIIString()?.let {
        sha256Hex("paper-trail-laya-system-one-endpoint-v1\n$it".toByteArray(Charsets.UTF_8))
    }
    val isConfigurationValid: Boolean = endpointUri != null &&
        requestTimeoutMillis in 1..MAX_REQUEST_TIMEOUT_MILLIS &&
        !apiKey.isNullOrBlank() && apiKey.none(Char::isISOControl)
    val isSelectable: Boolean = enabled && isConfigurationValid && trustBoundary == ProviderTrustBoundary.LOCAL

    init {
        require(trustedHosts.none(String::isBlank)) { "Laya trusted hosts must not contain blank entries." }
    }

    override fun toString(): String =
        "LayaSystemOneSettings(enabled=$enabled, trustBoundary=${trustBoundary.id}, " +
            "apiKeyConfigured=${!apiKey.isNullOrBlank()}, requestTimeoutMillis=$requestTimeoutMillis)"

    companion object {
        const val PROVIDER_ID = "laya"
        const val REQUEST_MODEL_ALIAS = "typed-decisions"
        const val PINNED_MODEL_ID = "convaiinnovations/laya-typed-decisions@1a793eb568e6718f15941d08f85432581df534e3"
        const val PINNED_RUNTIME_VERSION = "laya-serve-0.3.20@23a17522aa4942da6cce53a995a275760320b691"
        // Bump whenever the six question definitions, instructions, or scoring rubrics change.
        const val PROMPT_VERSION_ID = "paper-trail-laya-evidence-judgement-prompt-v1"
        const val OUTPUT_MAPPING_VERSION = "paper-trail-evidence-judgement-v1"
        const val OUTPUT_MAPPING_VERSION_ID = "pt-ej-v1"
        const val PROVIDER_VERSION = "$PINNED_RUNTIME_VERSION/$OUTPUT_MAPPING_VERSION_ID"
        const val DEFAULT_REQUEST_TIMEOUT_MILLIS = 120_000L
        const val MAX_REQUEST_TIMEOUT_MILLIS = 600_000L
        const val MODEL_CONTEXT_TOKENS = 1_024
        const val MAX_RESPONSE_BYTES = 1_000_000

        fun disabled(): LayaSystemOneSettings = LayaSystemOneSettings(
            enabled = false,
            baseUrl = "",
        )

        private fun normalizeHost(host: String): String = host
            .removePrefix("[")
            .removeSuffix("]")
            .trimEnd('.')
            .lowercase()

        private fun parseBaseUri(value: String): URI? = runCatching {
            val uri = URI(value.trim())
            if (uri.scheme?.lowercase() !in setOf("http", "https") ||
                uri.host.isNullOrBlank() || uri.userInfo != null || uri.query != null || uri.fragment != null ||
                uri.path !in setOf("", "/") || uri.port == 0 || uri.port !in -1..65535
            ) {
                return null
            }
            URI(uri.scheme.lowercase(), null, uri.host, uri.port, null, null, null)
        }.getOrNull()
    }
}
