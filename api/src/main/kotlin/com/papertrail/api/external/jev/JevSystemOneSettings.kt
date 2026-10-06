package com.papertrail.api.external.jev

import com.papertrail.api.infrastructure.crypto.sha256Hex
import com.papertrail.api.infrastructure.providers.ProviderTrustBoundary
import java.net.URI

/** Server-side settings for the hosted TypeSafe System One endpoint. */
data class JevSystemOneSettings(
    val apiKey: String? = null,
    val modelId: String = DEFAULT_MODEL_ID,
    val baseUrl: String = DEFAULT_BASE_URL,
    val requestTimeoutMillis: Long = DEFAULT_REQUEST_TIMEOUT_MILLIS,
    val retentionDisclosure: String? = null,
) {
    val endpointUri: URI? = parseBaseUri(baseUrl)?.let { base ->
        URI("${base.toASCIIString().trimEnd('/')}/v1/systemone")
    }
    val configurationFingerprint: String? = endpointUri?.toASCIIString()?.let {
        sha256Hex("paper-trail-jev-system-one-endpoint-v1\n$it".toByteArray(Charsets.UTF_8))
    }
    val isConfigurationValid: Boolean = endpointUri != null &&
        !apiKey.isNullOrBlank() && apiKey.none(Char::isISOControl) &&
        modelId.length in 1..MAX_MODEL_ID_LENGTH && modelId.isNotBlank() && modelId.none(Char::isISOControl) &&
        requestTimeoutMillis in 1..MAX_REQUEST_TIMEOUT_MILLIS &&
        retentionDisclosure?.none(Char::isISOControl) != false
    val isSelectable: Boolean = isConfigurationValid
    val trustBoundary: ProviderTrustBoundary = if (endpointUri != null) {
        ProviderTrustBoundary.EXTERNAL
    } else {
        ProviderTrustBoundary.UNREVIEWED
    }

    init {
        require(retentionDisclosure?.none(Char::isISOControl) != false) {
            "Jev retention disclosure must not contain control characters."
        }
    }

    override fun toString(): String =
        "JevSystemOneSettings(modelId=$modelId, trustBoundary=${trustBoundary.id}, " +
            "apiKeyConfigured=${!apiKey.isNullOrBlank()}, requestTimeoutMillis=$requestTimeoutMillis)"

    companion object {
        const val PROVIDER_ID = "jev"
        const val DEFAULT_MODEL_ID = "jev-latest"
        const val DEFAULT_BASE_URL = "https://api.typesafe.ai"
        const val OUTPUT_MAPPING_VERSION = "paper-trail-evidence-judgement-v1"
        const val PROVIDER_VERSION = "typesafe-system-one-v1/$OUTPUT_MAPPING_VERSION"
        const val DEFAULT_REQUEST_TIMEOUT_MILLIS = 120_000L
        const val MAX_REQUEST_TIMEOUT_MILLIS = 600_000L
        const val MAX_RESPONSE_BYTES = 1_000_000
        const val MAX_MODEL_ID_LENGTH = 128

        fun disabled(): JevSystemOneSettings = JevSystemOneSettings()

        private fun parseBaseUri(value: String): URI? = runCatching {
            val uri = URI(value.trim())
            val host = uri.host ?: return null
            val localHttp = uri.scheme.equals("http", ignoreCase = true) &&
                host in setOf("localhost", "127.0.0.1", "::1")
            if (uri.scheme?.lowercase() != "https" && !localHttp ||
                uri.userInfo != null || uri.query != null || uri.fragment != null ||
                uri.path !in setOf("", "/") || uri.port == 0 || uri.port !in -1..65535
            ) {
                return null
            }
            URI(uri.scheme.lowercase(), null, host, uri.port, null, null, null)
        }.getOrNull()
    }
}
