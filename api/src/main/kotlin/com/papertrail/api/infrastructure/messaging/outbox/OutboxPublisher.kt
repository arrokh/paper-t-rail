package com.papertrail.api.infrastructure.messaging.outbox

import com.papertrail.api.infrastructure.messaging.repository.OutboxRepository
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.util.UUID

@Component
@ConditionalOnProperty(prefix = "paper-trail", name = ["role"], havingValue = "api", matchIfMissing = true)
class OutboxPublisher(
    private val outboxRepository: OutboxRepository,
    private val redis: StringRedisTemplate,
    @Value("\${paper-trail.queue.stream}") private val stream: String,
) {
    @Scheduled(fixedDelayString = "\${paper-trail.queue.publish-delay-ms:250}")
    fun publishPending() {
        val events = outboxRepository.pendingEvents()

        events.forEach { event ->
            val previousContext = MDC.getCopyOfContextMap()
            MDC.put("eventId", event.id.toString())
            MDC.put("analysisRunId", event.analysisRunId.toString())
            MDC.put("correlationId", event.correlationId.toString())
            try {
                redis.opsForStream<String, String>().add(stream, mapOf("event" to event.payload))
                outboxRepository.markPublished(event.id)
                logger.info("Outbox event published")
            } catch (exception: Exception) {
                // Leave the outbox row unpublished: a later pass safely republishes it.
                logger.atWarn()
                    .addKeyValue("errorType", exception.javaClass.simpleName)
                    .log("Outbox event could not be published; it will be retried")
            } finally {
                if (previousContext == null) MDC.clear() else MDC.setContextMap(previousContext)
            }
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(OutboxPublisher::class.java)
    }
}
