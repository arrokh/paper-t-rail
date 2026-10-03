package com.papertrail.api.citation.claims.provider

import com.papertrail.api.citation.claims.domain.ClaimAnalysisVersions
import com.papertrail.api.infrastructure.crypto.sha256Hex
import com.papertrail.api.infrastructure.providers.ProviderTrustBoundary
import java.net.URI

/** Server-only endpoint settings; neither credentials nor endpoint details are returned by the provider directory. */
data class OpenAiCompatibleClaimAnalysisSettings(
    val enabled: Boolean,
    val baseUrl: String,
    val modelId: String,
    val apiKey: String? = null,
    val trustedHosts: Set<String> = emptySet(),
    val contextWindowTokens: Int = DEFAULT_CONTEXT_WINDOW_TOKENS,
    val maxCompletionTokens: Int = DEFAULT_MAX_COMPLETION_TOKENS,
    val maxRequestBytes: Int = DEFAULT_MAX_REQUEST_BYTES,
    val maxResponseBytes: Int = DEFAULT_MAX_RESPONSE_BYTES,
    val requestTimeoutMillis: Long = DEFAULT_REQUEST_TIMEOUT_MILLIS,
    val enablementReviewed: Boolean = false,
    val retentionDisclosure: String? = null,
) {
    val baseUri: URI? = parseBaseUri(baseUrl)
    private val versionedApiBaseUri: URI? = baseUri?.let(::withApiVersion)
    val endpointUri: URI? = versionedApiBaseUri?.let { URI("${it.toASCIIString().trimEnd('/')}/chat/completions") }
    val modelsUri: URI? = versionedApiBaseUri?.let { URI("${it.toASCIIString().trimEnd('/')}/models") }
    private val normalizedTrustedHosts = trustedHosts.map(::normalizeHost).toSet()
    val isConfigurationValid: Boolean = baseUri != null &&
        baseUrl.length in 1..MAX_BASE_URL_LENGTH && baseUrl == baseUrl.trim() &&
        MODEL_ID_PATTERN.matches(modelId) &&
        contextWindowTokens in 2..MAX_CONTEXT_WINDOW_TOKENS &&
        maxCompletionTokens in 1 until contextWindowTokens &&
        maxRequestBytes in 1..MAX_REQUEST_BYTES_LIMIT &&
        maxResponseBytes in 1..MAX_RESPONSE_BYTES_LIMIT &&
        requestTimeoutMillis in 1..MAX_REQUEST_TIMEOUT_MILLIS &&
        apiKey.orEmpty().length <= MAX_API_KEY_LENGTH && apiKey.orEmpty().none(Char::isISOControl) &&
        (retentionDisclosure == null || retentionDisclosure.length <= MAX_RETENTION_DISCLOSURE_LENGTH)
    val trustBoundary: ProviderTrustBoundary = baseUri?.host
        ?.let(::normalizeHost)
        ?.let { host -> if (host in normalizedTrustedHosts) ProviderTrustBoundary.LOCAL else ProviderTrustBoundary.EXTERNAL }
        ?: ProviderTrustBoundary.UNREVIEWED
    val configurationFingerprint: String? = baseUri?.toASCIIString()?.let { endpoint ->
        sha256Hex(
            listOf(
                "paper-trail-openai-compatible-claim-analysis-v1",
                endpoint,
                modelId,
                contextWindowTokens,
                maxCompletionTokens,
                maxRequestBytes,
                maxResponseBytes,
                requestTimeoutMillis,
                !apiKey.isNullOrBlank(),
                ClaimAnalysisVersions.MODEL_TARGET_SELECTION_POLICY,
                ClaimAnalysisVersions.OPENAI_COMPATIBLE_PROMPT,
                ClaimAnalysisVersions.OPENAI_COMPATIBLE_OUTPUT_MAPPING,
            ).joinToString("\n").toByteArray(Charsets.UTF_8),
        )
    }
    val isSelectable: Boolean = enabled && isConfigurationValid &&
        (trustBoundary == ProviderTrustBoundary.LOCAL ||
            (trustBoundary == ProviderTrustBoundary.EXTERNAL && baseUri?.scheme.equals("https", ignoreCase = true) &&
                enablementReviewed && !retentionDisclosure.isNullOrBlank()))

    init {
        require(trustedHosts.none(String::isBlank)) { "OpenAI-compatible claim-analysis trusted hosts must not contain blank entries." }
    }

    override fun toString(): String =
        "OpenAiCompatibleClaimAnalysisSettings(enabled=$enabled, modelId=$modelId, " +
            "contextWindowTokens=$contextWindowTokens, trustBoundary=${trustBoundary.id}, " +
            "apiKeyConfigured=${!apiKey.isNullOrBlank()})"

    private fun normalizeHost(host: String): String = host
        .removePrefix("[")
        .removeSuffix("]")
        .trimEnd('.')
        .lowercase()

    companion object {
        const val PROVIDER_ID = "openai-compatible-chat"
        const val VERSION = "v1"
        const val DEFAULT_CONTEXT_WINDOW_TOKENS = 32_768
        const val DEFAULT_MAX_COMPLETION_TOKENS = 2_048
        const val DEFAULT_MAX_REQUEST_BYTES = 1_048_576
        const val DEFAULT_MAX_RESPONSE_BYTES = 1_048_576
        const val DEFAULT_REQUEST_TIMEOUT_MILLIS = 60_000L
        const val MAX_CONTEXT_WINDOW_TOKENS = 2_000_000
        const val MAX_BASE_URL_LENGTH = 2_048
        const val MAX_API_KEY_LENGTH = 4_096
        const val MAX_REQUEST_BYTES_LIMIT = 10_000_000
        const val MAX_RESPONSE_BYTES_LIMIT = 10_000_000
        const val MAX_REQUEST_TIMEOUT_MILLIS = 600_000L
        const val MAX_RETENTION_DISCLOSURE_LENGTH = 2_000

        private val MODEL_ID_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._:/-]{0,159}")
        private val API_VERSION_SEGMENT_PATTERN = Regex("v[0-9]+")

        fun disabled(): OpenAiCompatibleClaimAnalysisSettings = OpenAiCompatibleClaimAnalysisSettings(
            enabled = false,
            baseUrl = "",
            modelId = "",
        )

        private fun withApiVersion(baseUri: URI): URI {
            val normalizedBase = baseUri.toASCIIString().trimEnd('/')
            val lastPathSegment = baseUri.path.trimEnd('/').substringAfterLast('/')
            return if (API_VERSION_SEGMENT_PATTERN.matches(lastPathSegment)) URI(normalizedBase)
            else URI("$normalizedBase/v1")
        }

        private fun parseBaseUri(value: String): URI? = runCatching {
            val uri = URI(value.trim())
            if (uri.scheme?.lowercase() !in setOf("http", "https") ||
                uri.host.isNullOrBlank() || uri.userInfo != null || uri.query != null || uri.fragment != null ||
                uri.port == 0 || uri.port !in -1..65535 ||
                uri.path.split('/').any { it == "." || it == ".." }
            ) {
                return null
            }
            uri
        }.getOrNull()
    }
}
