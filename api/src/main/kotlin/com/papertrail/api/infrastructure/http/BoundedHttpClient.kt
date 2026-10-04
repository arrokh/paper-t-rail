package com.papertrail.api.infrastructure.http

import java.io.ByteArrayOutputStream
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ExecutionException
import java.util.concurrent.Flow
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class BoundedHttpClient(connectTimeoutSeconds: Long) {
    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(connectTimeoutSeconds))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    fun send(request: HttpRequest, timeoutMillis: Long, maxResponseBytes: Int): HttpResponse<ByteArray> {
        require(timeoutMillis > 0) { "HTTP request timeout must be positive." }
        require(maxResponseBytes > 0) { "HTTP response limit must be positive." }
        val response = client.sendAsync(request, boundedResponseBodyHandler(maxResponseBytes))
        return try {
            response.get(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            response.cancel(true)
            throw BoundedHttpRequestException(BoundedHttpRequestException.Reason.TIMEOUT)
        } catch (failure: ExecutionException) {
            if (failure.cause is java.net.http.HttpTimeoutException) {
                throw BoundedHttpRequestException(BoundedHttpRequestException.Reason.TIMEOUT)
            }
            throw BoundedHttpRequestException(BoundedHttpRequestException.Reason.UNAVAILABLE)
        } catch (_: InterruptedException) {
            response.cancel(true)
            Thread.currentThread().interrupt()
            throw BoundedHttpRequestException(BoundedHttpRequestException.Reason.INTERRUPTED)
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
}
