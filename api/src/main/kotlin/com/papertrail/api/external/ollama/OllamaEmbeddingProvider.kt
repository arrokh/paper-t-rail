package com.papertrail.api.external.ollama

import com.fasterxml.jackson.databind.JsonNode
import com.papertrail.api.utils.JsonUtil
import com.papertrail.api.infrastructure.providers.DataCategory
import com.papertrail.api.infrastructure.providers.EMBEDDING_ROLE
import com.papertrail.api.infrastructure.providers.ProviderCallGate
import com.papertrail.api.infrastructure.providers.ProviderCallPayload
import com.papertrail.api.infrastructure.http.BoundedHttpClient
import com.papertrail.api.infrastructure.http.BoundedHttpRequestException
import org.springframework.stereotype.Component
import java.io.IOException
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

import com.papertrail.api.evidence.embedding.EmbeddingProvider
import com.papertrail.api.evidence.embedding.EmbeddingRequestContext

@Component
class OllamaEmbeddingProvider(
    private val settings: OllamaEmbeddingSettings,
    private val providerCallGate: ProviderCallGate,
) : EmbeddingProvider {
    override val providerId = settings.providerId
    override val modelId: String = settings.modelId
    override val version = OllamaEmbeddingSettings.VERSION
    override val dimension: Int = settings.dimension

    private val httpClient = BoundedHttpClient(CONNECT_TIMEOUT_SECONDS)

    override fun embed(text: String, context: EmbeddingRequestContext): FloatArray {
        require(text.isNotBlank()) { "Embedding input must not be blank." }
        if (!settings.isConfigurationValid || settings.endpointUri == null || settings.configurationFingerprint == null) {
            throw OllamaEmbeddingException("Ollama embedding is not configured with a valid endpoint, model, and dimension.")
        }
        require(context.inputCategory in EmbeddingRequestContext.SUPPORTED_INPUT_CATEGORIES) {
            "Ollama embedding input category is not supported."
        }

        val payload = ProviderCallPayload(
            mapOf(
                context.inputCategory to JsonUtil.toTree(text),
                DataCategory.EMBEDDING_INPUT to JsonUtil.toTree(text),
            ),
        )
        return providerCallGate.call(
            role = EMBEDDING_ROLE,
            providerId = providerId,
            payload = payload,
            configuration = context.configuration,
        ) { authorizedPayload ->
            requestEmbedding(authorizedPayload.contentByCategory.getValue(DataCategory.EMBEDDING_INPUT).asText())
        }
    }

    private fun requestEmbedding(text: String): FloatArray {
        val endpoint = settings.endpointUri ?: throw OllamaEmbeddingException("Ollama embedding endpoint is not configured.")
        val requestBody = try {
            JsonUtil.toJsonBytes(
                mapOf(
                    "model" to settings.modelId,
                    "input" to listOf(text),
                    "truncate" to false,
                ),
            )
        } catch (_: IOException) {
            throw OllamaEmbeddingException("Ollama embedding request could not be encoded.")
        }
        val requestBuilder = HttpRequest.newBuilder(endpoint)
            .timeout(Duration.ofMillis(settings.requestTimeoutMillis))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofByteArray(requestBody))
        settings.apiKey?.takeIf(String::isNotBlank)?.let { requestBuilder.header("Authorization", "Bearer $it") }

        val response = sendRequest(requestBuilder.build())
        if (response.statusCode() !in 200..299) {
            throw OllamaEmbeddingException("Ollama embedding endpoint returned HTTP ${response.statusCode()}.")
        }

        val responseBody = response.body()
        if (responseBody.size > OllamaEmbeddingSettings.MAX_RESPONSE_BYTES) {
            throw OllamaEmbeddingException("Ollama embedding response exceeded the configured response limit.")
        }
        return parseEmbeddingResponse(responseBody)
    }

    private fun sendRequest(request: HttpRequest): HttpResponse<ByteArray> = try {
        httpClient.send(request, settings.requestTimeoutMillis, OllamaEmbeddingSettings.MAX_RESPONSE_BYTES)
    } catch (failure: BoundedHttpRequestException) {
        val message = when (failure.reason) {
            BoundedHttpRequestException.Reason.TIMEOUT -> "Ollama embedding request timed out."
            BoundedHttpRequestException.Reason.UNAVAILABLE -> "Ollama embedding endpoint is unavailable."
            BoundedHttpRequestException.Reason.INTERRUPTED -> "Ollama embedding request was interrupted."
        }
        throw OllamaEmbeddingException(message)
    }

    private fun parseEmbeddingResponse(responseBody: ByteArray): FloatArray {
        val response = try {
            JsonUtil.parseTree(responseBody)
        } catch (_: IOException) {
            throw OllamaEmbeddingException("Ollama returned a malformed embedding response.")
        }
        if (response == null || !response.isObject || !response.path("model").isTextual ||
            response.path("model").asText() != settings.modelId
        ) {
            throw OllamaEmbeddingException("Ollama response does not identify the configured embedding model.")
        }
        val embeddings = response.path("embeddings")
        if (!embeddings.isArray || embeddings.size() != 1 || !embeddings[0].isArray) {
            throw OllamaEmbeddingException("Ollama returned a malformed embedding response.")
        }
        val vector = embeddings[0]
        if (vector.size() != dimension) {
            throw OllamaEmbeddingException("Ollama returned an embedding dimension that does not match the configured dimension.")
        }
        return FloatArray(vector.size()) { index ->
            val value = vector[index]
            if (!value.isNumber || !value.floatValue().isFinite()) {
                throw OllamaEmbeddingException("Ollama returned a malformed embedding vector.")
            }
            value.floatValue()
        }
    }

    companion object {
        private const val CONNECT_TIMEOUT_SECONDS = 5L
    }
}
