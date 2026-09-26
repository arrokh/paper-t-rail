package com.papertrail.api.evidence.embedding

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.papertrail.api.infrastructure.providers.DataCategory
import com.papertrail.api.infrastructure.providers.EMBEDDING_ROLE
import com.papertrail.api.infrastructure.providers.ProviderCallGate
import com.papertrail.api.infrastructure.providers.ProviderCallPayload
import org.springframework.stereotype.Component
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.nio.ByteBuffer
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ExecutionException
import java.util.concurrent.Flow
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

@Component
class OllamaEmbeddingProvider(
    private val settings: OllamaEmbeddingSettings,
    private val providerCallGate: ProviderCallGate,
    private val objectMapper: ObjectMapper,
) : EmbeddingProvider {
    override val providerId = OllamaEmbeddingSettings.PROVIDER_ID
    override val modelId: String = settings.modelId
    override val version = OllamaEmbeddingSettings.VERSION
    override val dimension: Int = settings.dimension

    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

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
                context.inputCategory to objectMapper.valueToTree<JsonNode>(text),
                DataCategory.EMBEDDING_INPUT to objectMapper.valueToTree<JsonNode>(text),
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
            objectMapper.writeValueAsBytes(
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

    private fun sendRequest(request: HttpRequest): HttpResponse<ByteArray> {
        val responseFuture = httpClient.sendAsync(
            request,
            boundedResponseBodyHandler(OllamaEmbeddingSettings.MAX_RESPONSE_BYTES),
        )
        return try {
            responseFuture.get(settings.requestTimeoutMillis, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            responseFuture.cancel(true)
            throw OllamaEmbeddingException("Ollama embedding request timed out.")
        } catch (failure: ExecutionException) {
            if (failure.cause is HttpTimeoutException) {
                throw OllamaEmbeddingException("Ollama embedding request timed out.")
            }
            throw OllamaEmbeddingException("Ollama embedding endpoint is unavailable.")
        } catch (_: InterruptedException) {
            responseFuture.cancel(true)
            Thread.currentThread().interrupt()
            throw OllamaEmbeddingException("Ollama embedding request was interrupted.")
        }
    }

    private fun boundedResponseBodyHandler(maxBytes: Int): HttpResponse.BodyHandler<ByteArray> =
        HttpResponse.BodyHandler {
            object : HttpResponse.BodySubscriber<ByteArray> {
                private val body = ByteArrayOutputStream()
                private val result = CompletableFuture<ByteArray>()
                private lateinit var subscription: Flow.Subscription

                override fun getBody(): CompletionStage<ByteArray> = result

                override fun onSubscribe(subscription: Flow.Subscription) {
                    this.subscription = subscription
                    subscription.request(1)
                }

                override fun onNext(items: List<ByteBuffer>) {
                    for (buffer in items) {
                        if (buffer.remaining() > maxBytes - body.size()) {
                            subscription.cancel()
                            result.complete(ByteArray(maxBytes + 1))
                            return
                        }
                        val bytes = ByteArray(buffer.remaining())
                        buffer.get(bytes)
                        body.write(bytes, 0, bytes.size)
                    }
                    subscription.request(1)
                }

                override fun onError(throwable: Throwable) {
                    result.completeExceptionally(throwable)
                }

                override fun onComplete() {
                    result.complete(body.toByteArray())
                }
            }
        }

    private fun parseEmbeddingResponse(responseBody: ByteArray): FloatArray {
        val response = try {
            objectMapper.readTree(responseBody)
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
