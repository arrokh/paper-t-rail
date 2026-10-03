package com.papertrail.api.infrastructure.providers.openai

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.io.ByteArrayOutputStream
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

/** Shared, bounded Chat Completions transport for explicitly selected OpenAI-compatible adapters. */
@Component
class OpenAiCompatibleChatClient(
    private val settings: OpenAiCompatibleEndpointSettings,
    private val objectMapper: ObjectMapper,
) {
    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    fun requestBody(modelId: String, maxCompletionTokens: Int, systemPrompt: String, userJson: String): ByteArray {
        val root = objectMapper.createObjectNode()
        root.put("model", modelId)
        root.put("temperature", 0)
        root.put("max_tokens", maxCompletionTokens)
        root.put("stream", false)
        root.set<ObjectNode>("response_format", objectMapper.createObjectNode().put("type", "json_object"))
        root.set<JsonNode>("messages", objectMapper.createArrayNode().apply {
            add(objectMapper.createObjectNode().put("role", "system").put("content", systemPrompt))
            add(objectMapper.createObjectNode().put("role", "user").put("content", userJson))
        })
        return try {
            objectMapper.writeValueAsBytes(root)
        } catch (exception: Exception) {
            throw OpenAiCompatibleProviderException("The OpenAI-compatible request could not be serialized.")
        }
    }

    /** Checks endpoint reachability without sending a content-bearing request payload. */
    fun validateAvailability() {
        val endpoint = settings.modelsUri
            ?: throw OpenAiCompatibleProviderException("The OpenAI-compatible endpoint is not configured.")
        if (!settings.isSelectable) {
            throw OpenAiCompatibleProviderException("The OpenAI-compatible endpoint is not selectable.")
        }
        val startedAtNanos = System.nanoTime()
        logger.atInfo()
            .addKeyValue("providerId", OpenAiCompatibleEndpointSettings.PROVIDER_ID)
            .addKeyValue("operation", "availability-check")
            .log("OpenAI-compatible availability check started")
        val requestBuilder = HttpRequest.newBuilder(endpoint)
            .timeout(Duration.ofMillis(AVAILABILITY_TIMEOUT_MILLIS))
            .header("Accept", "application/json")
            .GET()
        settings.apiKey?.takeIf(String::isNotBlank)?.let { requestBuilder.header("Authorization", "Bearer $it") }
        val responseFuture = try {
            httpClient.sendAsync(requestBuilder.build(), HttpResponse.BodyHandlers.discarding())
        } catch (exception: Exception) {
            logTransportFailure("availability-check", exception, startedAtNanos)
            throw RetryableOpenAiCompatibleProviderException("The OpenAI-compatible endpoint could not be reached.")
        }
        val response = try {
            responseFuture.get(AVAILABILITY_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        } catch (exception: InterruptedException) {
            responseFuture.cancel(true)
            Thread.currentThread().interrupt()
            logTransportFailure("availability-check", exception, startedAtNanos)
            throw RetryableOpenAiCompatibleProviderException("The OpenAI-compatible endpoint availability check was interrupted.")
        } catch (exception: TimeoutException) {
            responseFuture.cancel(true)
            logTransportFailure("availability-check", exception, startedAtNanos)
            throw RetryableOpenAiCompatibleProviderException("The OpenAI-compatible endpoint availability check timed out.")
        } catch (exception: Exception) {
            logTransportFailure("availability-check", exception, startedAtNanos)
            throw RetryableOpenAiCompatibleProviderException("The OpenAI-compatible endpoint could not be reached.")
        }
        logHttpStatus("availability-check", response.statusCode(), startedAtNanos)
        if (response.statusCode() == 408 || response.statusCode() == 429 || response.statusCode() in 500..599) {
            throw RetryableOpenAiCompatibleProviderException(
                "The OpenAI-compatible endpoint availability check returned retryable HTTP ${response.statusCode()}.",
            )
        }
        if (response.statusCode() !in 200..299) {
            throw OpenAiCompatibleProviderException(
                "The OpenAI-compatible endpoint availability check returned HTTP ${response.statusCode()}.",
            )
        }
    }

    fun complete(requestBody: ByteArray): String {
        val endpoint = settings.chatCompletionsUri
            ?: throw OpenAiCompatibleProviderException("The OpenAI-compatible endpoint is not configured.")
        if (!settings.isSelectable) {
            throw OpenAiCompatibleProviderException("The OpenAI-compatible endpoint is not selectable.")
        }
        if (requestBody.size > settings.maxRequestBytes) {
            throw OpenAiCompatibleProviderException("The OpenAI-compatible request exceeds the configured limit.")
        }
        val startedAtNanos = System.nanoTime()
        logger.atDebug()
            .addKeyValue("providerId", OpenAiCompatibleEndpointSettings.PROVIDER_ID)
            .addKeyValue("operation", "chat-completion")
            .addKeyValue("requestBytes", requestBody.size)
            .log("OpenAI-compatible chat completion started")
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
            logTransportFailure("chat-completion", exception, startedAtNanos)
            throw RetryableOpenAiCompatibleProviderException("The OpenAI-compatible endpoint could not be reached.")
        }
        val response = try {
            responseFuture.get(settings.requestTimeoutMillis, TimeUnit.MILLISECONDS)
        } catch (exception: InterruptedException) {
            responseFuture.cancel(true)
            Thread.currentThread().interrupt()
            logTransportFailure("chat-completion", exception, startedAtNanos)
            throw RetryableOpenAiCompatibleProviderException("The OpenAI-compatible request was interrupted.")
        } catch (exception: TimeoutException) {
            responseFuture.cancel(true)
            logTransportFailure("chat-completion", exception, startedAtNanos)
            throw RetryableOpenAiCompatibleProviderException("The OpenAI-compatible request timed out.")
        } catch (exception: Exception) {
            logTransportFailure("chat-completion", exception, startedAtNanos)
            throw RetryableOpenAiCompatibleProviderException("The OpenAI-compatible endpoint could not be reached.")
        }
        val body = response.body()
        if (body.size > settings.maxResponseBytes) {
            logger.atWarn()
                .addKeyValue("providerId", OpenAiCompatibleEndpointSettings.PROVIDER_ID)
                .addKeyValue("responseBytes", body.size)
                .addKeyValue("maxResponseBytes", settings.maxResponseBytes)
                .addKeyValue("durationMs", elapsedMillis(startedAtNanos))
                .log("OpenAI-compatible chat response exceeds its configured byte limit")
            throw OpenAiCompatibleProviderException("The OpenAI-compatible response exceeds the configured limit.")
        }
        logHttpStatus("chat-completion", response.statusCode(), startedAtNanos, body.size)
        if (response.statusCode() == 408 || response.statusCode() == 429 || response.statusCode() in 500..599) {
            throw RetryableOpenAiCompatibleProviderException(
                "The OpenAI-compatible endpoint returned retryable HTTP ${response.statusCode()}.",
            )
        }
        if (response.statusCode() !in 200..299) {
            throw OpenAiCompatibleProviderException("The OpenAI-compatible endpoint returned HTTP ${response.statusCode()}.")
        }
        val contentType = response.headers().firstValue("Content-Type").orElse("")
        if (!contentType.substringBefore(';').trim().equals("application/json", ignoreCase = true) &&
            !contentType.substringBefore(';').trim().endsWith("+json", ignoreCase = true)
        ) {
            logger.atWarn()
                .addKeyValue("providerId", OpenAiCompatibleEndpointSettings.PROVIDER_ID)
                .addKeyValue("errorType", "NonJsonResponse")
                .addKeyValue("durationMs", elapsedMillis(startedAtNanos))
                .log("OpenAI-compatible chat endpoint returned a non-JSON response")
            throw OpenAiCompatibleProviderException("The OpenAI-compatible endpoint returned a non-JSON response.")
        }
        return body.toString(Charsets.UTF_8)
    }

    private fun logHttpStatus(
        operation: String,
        statusCode: Int,
        startedAtNanos: Long,
        responseBytes: Int? = null,
    ) {
        val success = statusCode in 200..299
        val event = when {
            !success -> logger.atWarn()
            operation == "availability-check" -> logger.atInfo()
            else -> logger.atDebug()
        }
        event
            .addKeyValue("providerId", OpenAiCompatibleEndpointSettings.PROVIDER_ID)
            .addKeyValue("operation", operation)
            .addKeyValue("httpStatus", statusCode)
            .addKeyValue("retryable", statusCode == 408 || statusCode == 429 || statusCode in 500..599)
            .addKeyValue("durationMs", elapsedMillis(startedAtNanos))
            .also { builder -> responseBytes?.let { builder.addKeyValue("responseBytes", it) } }
            .log(if (success) "OpenAI-compatible HTTP request succeeded" else "OpenAI-compatible HTTP request returned a failure status")
    }

    private fun logTransportFailure(operation: String, exception: Exception, startedAtNanos: Long) {
        logger.atWarn()
            .addKeyValue("providerId", OpenAiCompatibleEndpointSettings.PROVIDER_ID)
            .addKeyValue("operation", operation)
            .addKeyValue("errorType", exception.cause?.javaClass?.simpleName ?: exception.javaClass.simpleName)
            .addKeyValue("durationMs", elapsedMillis(startedAtNanos))
            .log("OpenAI-compatible transport failed before receiving a response")
    }

    private fun elapsedMillis(startedAtNanos: Long): Long =
        (System.nanoTime() - startedAtNanos) / NANOS_PER_MILLISECOND

    companion object {
        private const val CONNECT_TIMEOUT_SECONDS = 5L
        private const val AVAILABILITY_TIMEOUT_MILLIS = 5_000L
        private const val NANOS_PER_MILLISECOND = 1_000_000L
        private val logger = LoggerFactory.getLogger(OpenAiCompatibleChatClient::class.java)
    }
}

private class BoundedResponseBodySubscriber(
    private val maximumBytes: Int,
) : HttpResponse.BodySubscriber<ByteArray> {
    private val bytes = ByteArrayOutputStream(minOf(maximumBytes, INITIAL_BUFFER_BYTES))
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
