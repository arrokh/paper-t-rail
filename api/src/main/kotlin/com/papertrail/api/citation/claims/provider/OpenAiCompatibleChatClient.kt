package com.papertrail.api.citation.claims.provider

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.papertrail.api.infrastructure.messaging.NonRetryablePipelineException
import org.springframework.stereotype.Component
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.Flow
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class OpenAiCompatibleProviderException(message: String) : NonRetryablePipelineException(message)

class RetryableOpenAiCompatibleProviderException(message: String) : RuntimeException(message)

/** Shared, bounded Chat Completions transport for explicitly selected OpenAI-compatible adapters. */
@Component
class OpenAiCompatibleChatClient(
    private val settings: OpenAiCompatibleClaimAnalysisSettings,
    private val objectMapper: ObjectMapper,
) {
    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    fun requestBody(modelId: String, systemPrompt: String, userJson: String): ByteArray {
        val root = objectMapper.createObjectNode()
        root.put("model", modelId)
        root.put("temperature", 0)
        root.put("max_tokens", settings.maxCompletionTokens)
        root.put("stream", false)
        root.set<ObjectNode>("response_format", objectMapper.createObjectNode().put("type", "json_object"))
        root.set<JsonNode>("messages", objectMapper.createArrayNode().apply {
            add(objectMapper.createObjectNode().put("role", "system").put("content", systemPrompt))
            add(objectMapper.createObjectNode().put("role", "user").put("content", userJson))
        })
        return try {
            objectMapper.writeValueAsBytes(root)
        } catch (exception: Exception) {
            throw OpenAiCompatibleProviderException("The OpenAI-compatible claim-analysis request could not be serialized.")
        }
    }

    /** Checks endpoint reachability without sending claim, document, or other run payloads. */
    fun validateAvailability() {
        val endpoint = settings.modelsUri
            ?: throw OpenAiCompatibleProviderException("The OpenAI-compatible claim-analysis endpoint is not configured.")
        if (!settings.isSelectable) {
            throw OpenAiCompatibleProviderException("The OpenAI-compatible claim-analysis endpoint is not selectable.")
        }
        val requestBuilder = HttpRequest.newBuilder(endpoint)
            .timeout(Duration.ofMillis(AVAILABILITY_TIMEOUT_MILLIS))
            .header("Accept", "application/json")
            .GET()
        settings.apiKey?.takeIf(String::isNotBlank)?.let { requestBuilder.header("Authorization", "Bearer $it") }
        val responseFuture = try {
            httpClient.sendAsync(requestBuilder.build(), HttpResponse.BodyHandlers.discarding())
        } catch (exception: Exception) {
            throw RetryableOpenAiCompatibleProviderException("The OpenAI-compatible claim-analysis endpoint could not be reached.")
        }
        val response = try {
            responseFuture.get(AVAILABILITY_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        } catch (exception: InterruptedException) {
            responseFuture.cancel(true)
            Thread.currentThread().interrupt()
            throw RetryableOpenAiCompatibleProviderException("The OpenAI-compatible endpoint availability check was interrupted.")
        } catch (exception: TimeoutException) {
            responseFuture.cancel(true)
            throw RetryableOpenAiCompatibleProviderException("The OpenAI-compatible claim-analysis endpoint availability check timed out.")
        } catch (exception: Exception) {
            throw RetryableOpenAiCompatibleProviderException("The OpenAI-compatible claim-analysis endpoint could not be reached.")
        }
        if (response.statusCode() == 408 || response.statusCode() == 429 || response.statusCode() in 500..599) {
            throw RetryableOpenAiCompatibleProviderException(
                "The OpenAI-compatible claim-analysis endpoint availability check returned retryable HTTP ${response.statusCode()}.",
            )
        }
        if (response.statusCode() !in 200..299) {
            throw OpenAiCompatibleProviderException(
                "The OpenAI-compatible claim-analysis endpoint availability check returned HTTP ${response.statusCode()}.",
            )
        }
    }

    fun complete(requestBody: ByteArray): String {
        val endpoint = settings.endpointUri
            ?: throw OpenAiCompatibleProviderException("The OpenAI-compatible claim-analysis endpoint is not configured.")
        if (!settings.isSelectable) {
            throw OpenAiCompatibleProviderException("The OpenAI-compatible claim-analysis endpoint is not selectable.")
        }
        if (requestBody.size > settings.maxRequestBytes) {
            throw OpenAiCompatibleProviderException("The OpenAI-compatible claim-analysis request exceeds the configured limit.")
        }
        val requestBuilder = HttpRequest.newBuilder(endpoint)
            .timeout(Duration.ofMillis(settings.requestTimeoutMillis))
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofByteArray(requestBody))
        settings.apiKey?.takeIf(String::isNotBlank)?.let { requestBuilder.header("Authorization", "Bearer $it") }

        val responseFuture = try {
            httpClient.sendAsync(
                requestBuilder.build(),
                HttpResponse.BodyHandler { BoundedResponseBodySubscriber(settings.maxResponseBytes) },
            )
        } catch (exception: Exception) {
            throw RetryableOpenAiCompatibleProviderException("The OpenAI-compatible claim-analysis endpoint could not be reached.")
        }
        val response = try {
            responseFuture.get(settings.requestTimeoutMillis, TimeUnit.MILLISECONDS)
        } catch (exception: InterruptedException) {
            responseFuture.cancel(true)
            Thread.currentThread().interrupt()
            throw RetryableOpenAiCompatibleProviderException("The OpenAI-compatible claim-analysis request was interrupted.")
        } catch (exception: TimeoutException) {
            responseFuture.cancel(true)
            throw RetryableOpenAiCompatibleProviderException("The OpenAI-compatible claim-analysis request timed out.")
        } catch (exception: Exception) {
            throw RetryableOpenAiCompatibleProviderException("The OpenAI-compatible claim-analysis endpoint could not be reached.")
        }
        val body = response.body()
        if (body.size > settings.maxResponseBytes) {
            throw OpenAiCompatibleProviderException("The OpenAI-compatible claim-analysis response exceeds the configured limit.")
        }
        if (response.statusCode() == 408 || response.statusCode() == 429 || response.statusCode() in 500..599) {
            throw RetryableOpenAiCompatibleProviderException(
                "The OpenAI-compatible claim-analysis endpoint returned retryable HTTP ${response.statusCode()}.",
            )
        }
        if (response.statusCode() !in 200..299) {
            throw OpenAiCompatibleProviderException("The OpenAI-compatible claim-analysis endpoint returned HTTP ${response.statusCode()}.")
        }
        val contentType = response.headers().firstValue("Content-Type").orElse("")
        if (!contentType.substringBefore(';').trim().equals("application/json", ignoreCase = true) &&
            !contentType.substringBefore(';').trim().endsWith("+json", ignoreCase = true)
        ) {
            throw OpenAiCompatibleProviderException("The OpenAI-compatible claim-analysis endpoint returned a non-JSON response.")
        }
        return body.toString(Charsets.UTF_8)
    }

    companion object {
        private const val CONNECT_TIMEOUT_SECONDS = 5L
        private const val AVAILABILITY_TIMEOUT_MILLIS = 5_000L
    }
}

private class BoundedResponseBodySubscriber(
    private val maximumBytes: Int,
) : HttpResponse.BodySubscriber<ByteArray> {
    private val bytes = java.io.ByteArrayOutputStream(minOf(maximumBytes, INITIAL_BUFFER_BYTES))
    private val result = CompletableFuture<ByteArray>()
    private var subscription: Flow.Subscription? = null

    override fun getBody(): CompletionStage<ByteArray> = result

    override fun onSubscribe(subscription: Flow.Subscription) {
        this.subscription = subscription
        subscription.request(1)
    }

    override fun onNext(item: List<ByteBuffer>) {
        for (buffer in item) {
            val remainingCapacity = maximumBytes + 1 - bytes.size()
            val bytesToRead = minOf(buffer.remaining(), remainingCapacity)
            if (bytesToRead > 0) {
                val chunk = ByteArray(bytesToRead)
                buffer.get(chunk)
                bytes.write(chunk)
            }
            if (bytes.size() > maximumBytes) {
                subscription?.cancel()
                result.complete(bytes.toByteArray())
                return
            }
        }
        subscription?.request(1)
    }

    override fun onError(throwable: Throwable) {
        result.completeExceptionally(throwable)
    }

    override fun onComplete() {
        result.complete(bytes.toByteArray())
    }

    companion object {
        private const val INITIAL_BUFFER_BYTES = 8_192
    }
}
