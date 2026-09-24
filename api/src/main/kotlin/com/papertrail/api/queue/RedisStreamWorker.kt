package com.papertrail.api.queue

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.data.redis.connection.stream.Consumer
import org.springframework.data.redis.connection.stream.MapRecord
import org.springframework.data.redis.connection.stream.ReadOffset
import org.springframework.data.domain.Range
import org.springframework.data.redis.connection.stream.StreamOffset
import org.springframework.data.redis.connection.stream.StreamReadOptions
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Duration

@Component
@ConditionalOnProperty(prefix = "paper-trail", name = ["role"], havingValue = "worker")
class RedisStreamWorker(
    private val redis: StringRedisTemplate,
    private val handler: DocumentAnalysisRequestedHandler,
    private val objectMapper: ObjectMapper,
    @Value("\${paper-trail.queue.stream}") private val stream: String,
    @Value("\${paper-trail.queue.group}") private val group: String,
    @Value("\${paper-trail.queue.consumer}") private val consumerName: String,
    @Value("\${paper-trail.queue.reclaim-delay-ms}") private val reclaimDelayMs: Long,
    @Value("\${paper-trail.queue.batch-size}") private val batchSize: Long,
    @Value("\${paper-trail.queue.max-attempts}") private val maxAttempts: Long,
    @Value("\${paper-trail.queue.retry-backoff-ms}") retryBackoffMs: String,
) {
    private val retryBackoffDelaysMs = retryBackoffMs.split(',').map { it.trim().toLong() }

    init {
        require(maxAttempts > 0) { "Queue max attempts must be positive." }
        require(retryBackoffDelaysMs.size >= maxAttempts - 1) { "Queue retry backoff must define a delay between every attempt." }
        require(retryBackoffDelaysMs.all { it >= 0 }) { "Queue retry backoff values must not be negative." }
    }
    @Volatile
    private var groupReady = false

    @Volatile
    private var pendingScanCursor: String? = null

    @PostConstruct
    fun createConsumerGroup() {
        ensureConsumerGroup()
    }

    @Scheduled(fixedDelayString = "\${paper-trail.queue.poll-delay-ms}")
    fun poll() {
        if (!ensureConsumerGroup()) return
        try {
            val operations = redis.opsForStream<String, String>()
            val minIdle = Duration.ofMillis(reclaimDelayMs)
            val pendingRange = pendingScanCursor?.let { cursor ->
                Range.from(Range.Bound.exclusive(cursor)).to(Range.Bound.unbounded())
            } ?: Range.unbounded<String>()
            val pendingPageLimit = maxOf(batchSize * 100, 1000L)
            val pendingPage = operations.pending(stream, group, pendingRange, pendingPageLimit)
            pendingScanCursor = if (pendingPage.size().toLong() == pendingPageLimit) {
                pendingPage.lastOrNull()?.idAsString
            } else {
                null
            }
            val now = System.currentTimeMillis()
            val retryReady = pendingPage
                .filter { retryAfter(it.idAsString)?.let { retryAt -> retryAt <= now } == true }
                .take(batchSize.toInt())
            val pending = if (retryReady.isNotEmpty()) {
                retryReady
                    .groupBy { retryDelayMillis(it.totalDeliveryCount) }
                    .flatMap { (backoffMs, messagesForBackoff) ->
                        operations.claim(
                            stream,
                            group,
                            consumerName,
                            Duration.ofMillis(backoffMs),
                            *messagesForBackoff.map { it.id }.toTypedArray(),
                        )
                    }
                    .filter(::extendRetryLease)
            } else {
                val staleIds = pendingPage
                    .filter { retryAfter(it.idAsString) == null && it.elapsedTimeSinceLastDelivery >= minIdle }
                    .take(batchSize.toInt())
                    .map { it.id }
                if (staleIds.isEmpty()) {
                    emptyList()
                } else {
                    operations.claim(stream, group, consumerName, minIdle, *staleIds.toTypedArray())
                }
            }
            val messages = if (pending.isNotEmpty()) {
                pending
            } else {
                operations.read(
                    Consumer.from(group, consumerName),
                    StreamReadOptions.empty().count(batchSize),
                    StreamOffset.create(stream, ReadOffset.lastConsumed()),
                ).orEmpty()
            }
            messages.forEach(::process)
        } catch (exception: Exception) {
            logger.atWarn()
                .addKeyValue("errorType", exception.javaClass.simpleName)
                .log("Redis Streams poll failed; pending work will be retried")
        }
    }

    private fun process(record: MapRecord<String, String, String>) {
        val serialized = record.value["event"]
        if (serialized.isNullOrBlank()) {
            deadLetter(record, "MISSING_EVENT_ENVELOPE", "The stream entry did not contain an event envelope.")
            return
        }
        val event: PipelineEvent<DocumentAnalysisRequestedPayload> = try {
            objectMapper.readValue(serialized)
        } catch (exception: Exception) {
            deadLetter(record, "MALFORMED_EVENT_ENVELOPE", "The stream event envelope could not be parsed.")
            return
        }
        val previousContext = MDC.getCopyOfContextMap()
        MDC.put("analysisRunId", event.analysisRunId.toString())
        MDC.put("eventId", event.eventId.toString())
        MDC.put("correlationId", event.correlationId.toString())
        MDC.put("documentId", event.payload.documentId.toString())
        MDC.put("eventType", event.eventType)
        try {
            processEvent(record, serialized, event)
        } finally {
            if (previousContext == null) MDC.clear() else MDC.setContextMap(previousContext)
        }
    }

    private fun processEvent(
        record: MapRecord<String, String, String>,
        serialized: String,
        event: PipelineEvent<DocumentAnalysisRequestedPayload>,
    ) {
        if (event.eventType != DOCUMENT_ANALYSIS_REQUESTED) {
            deadLetter(record, "UNSUPPORTED_EVENT_TYPE", "No handler is registered for event type '${event.eventType}'.")
            return
        }
        try {
            handler.handle(serialized)
            acknowledge(record)
            logger.info("Pipeline event processed")
        } catch (exception: Exception) {
            val attempts = deliveryCount(record.id.value)
            if (attempts >= maxAttempts) {
                logger.atWarn()
                    .addKeyValue("attempt", attempts)
                    .addKeyValue("errorType", exception.javaClass.simpleName)
                    .log("Pipeline event failed on its final attempt")
                val reason = "The worker could not complete document analysis after $maxAttempts attempts. The event was moved to the dead-letter queue."
                try {
                    handler.markFailed(event, reason)
                    deadLetter(record, "HANDLER_RETRIES_EXHAUSTED", reason, attempts)
                } catch (failureException: Exception) {
                    logger.atError()
                        .addKeyValue("streamMessageId", record.id.value)
                        .addKeyValue("attempt", attempts)
                        .addKeyValue("errorType", failureException.javaClass.simpleName)
                        .log("Stream message could not be failed durably and remains pending")
                }
            } else {
                try {
                    scheduleRetry(record.id.value, attempts)
                    logger.atWarn()
                        .addKeyValue("streamMessageId", record.id.value)
                        .addKeyValue("attempt", attempts)
                        .addKeyValue("errorType", exception.javaClass.simpleName)
                        .log("Stream message failed; retry scheduled")
                } catch (scheduleException: Exception) {
                    logger.atWarn()
                        .addKeyValue("streamMessageId", record.id.value)
                        .addKeyValue("attempt", attempts)
                        .addKeyValue("errorType", scheduleException.javaClass.simpleName)
                        .addKeyValue("handlerErrorType", exception.javaClass.simpleName)
                        .log("Stream message failed; it remains pending for recovery")
                }
            }
        }
    }

    private fun deadLetter(record: MapRecord<String, String, String>, code: String, reason: String, attempts: Long? = null) {
        try {
            val originalEnvelope = record.value["event"] ?: ""
            val deadLetterEnvelope = attempts?.let { deliveryCount ->
                runCatching {
                    val event: PipelineEvent<DocumentAnalysisRequestedPayload> = objectMapper.readValue(originalEnvelope)
                    objectMapper.writeValueAsString(event.copy(attempt = deliveryCount.toInt()))
                }.getOrNull()
            } ?: originalEnvelope
            redis.opsForStream<String, String>().add(
                "ae:dlq",
                mapOf(
                    "sourceStream" to stream,
                    "sourceMessageId" to record.id.value,
                    "errorCode" to code,
                    "reason" to reason,
                    "attempts" to (attempts?.toString() ?: "1"),
                    "event" to deadLetterEnvelope,
                ),
            )
            acknowledge(record)
        } catch (exception: Exception) {
            logger.atWarn()
                .addKeyValue("streamMessageId", record.id.value)
                .addKeyValue("errorType", exception.javaClass.simpleName)
                .log("Stream message could not be dead-lettered and remains pending")
        }
    }

    private fun acknowledge(record: MapRecord<String, String, String>) {
        redis.opsForStream<String, String>().acknowledge(stream, group, record.id)
        redis.opsForHash<String, String>().delete(retryScheduleKey, record.id.value)
    }

    private fun deliveryCount(messageId: String): Long {
        val count = redis.opsForStream<String, String>()
            .pending(stream, group, Range.closed(messageId, messageId), 1)
            .firstOrNull()
            ?.totalDeliveryCount
        return count ?: throw IllegalStateException("Pending delivery count is unavailable for stream message $messageId.")
    }

    private fun retryAfter(messageId: String): Long? = redis.opsForHash<String, String>()
        .get(retryScheduleKey, messageId)
        ?.toLongOrNull()

    private fun retryDelayMillis(attempt: Long): Long =
        retryBackoffDelaysMs[(attempt - 1).coerceIn(0, retryBackoffDelaysMs.lastIndex.toLong()).toInt()]

    private fun extendRetryLease(record: MapRecord<String, String, String>): Boolean = try {
        val leaseExpiresAt = System.currentTimeMillis() + reclaimDelayMs
        redis.opsForHash<String, String>().put(retryScheduleKey, record.id.value, leaseExpiresAt.toString())
        true
    } catch (exception: Exception) {
        logger.atWarn()
            .addKeyValue("streamMessageId", record.id.value)
            .addKeyValue("errorType", exception.javaClass.simpleName)
            .log("Retry message could not obtain a processing lease")
        false
    }

    private fun scheduleRetry(messageId: String, attempt: Long) {
        val dueAt = System.currentTimeMillis() + retryDelayMillis(attempt)
        redis.opsForHash<String, String>().put(retryScheduleKey, messageId, dueAt.toString())
    }

    private fun ensureConsumerGroup(): Boolean {
        if (groupReady) return true
        synchronized(this) {
            if (groupReady) return true
            try {
                val operations = redis.opsForStream<String, String>()
                if (operations.groups(stream).any { it.groupName() == group }) {
                    groupReady = true
                    return true
                }
                operations.createGroup(stream, ReadOffset.from("0-0"), group)
                groupReady = true
            } catch (exception: Exception) {
                // The API creates the stream when the transactional outbox first publishes.
                logger.atDebug()
                    .addKeyValue("errorType", exception.javaClass.simpleName)
                    .log("Redis consumer group is not ready yet")
            }
        }
        return groupReady
    }

    private val retryScheduleKey: String
        get() = "$stream:retry-after"

    companion object {
        private val logger = LoggerFactory.getLogger(RedisStreamWorker::class.java)
    }
}
