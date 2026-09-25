package com.papertrail.api.infrastructure.messaging.outbox

import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.util.UUID

@Component
@ConditionalOnProperty(prefix = "paper-trail", name = ["role"], havingValue = "api", matchIfMissing = true)
class OutboxPublisher(
    private val jdbc: JdbcTemplate,
    private val redis: StringRedisTemplate,
    @Value("\${paper-trail.queue.stream}") private val stream: String,
) {
    @Scheduled(fixedDelayString = "\${paper-trail.queue.publish-delay-ms:250}")
    fun publishPending() {
        val events = jdbc.query(
            """
            SELECT event_id, analysis_run_id, correlation_id, payload::text AS payload
              FROM outbox_events
             WHERE published_at IS NULL
             ORDER BY created_at, event_id
             LIMIT 50
            """.trimIndent(),
            { rs, _ ->
                PendingEvent(
                    id = rs.getObject("event_id", UUID::class.java),
                    analysisRunId = rs.getObject("analysis_run_id", UUID::class.java),
                    correlationId = rs.getObject("correlation_id", UUID::class.java),
                    payload = rs.getString("payload"),
                )
            },
        )

        events.forEach { event ->
            val previousContext = MDC.getCopyOfContextMap()
            MDC.put("eventId", event.id.toString())
            MDC.put("analysisRunId", event.analysisRunId.toString())
            MDC.put("correlationId", event.correlationId.toString())
            try {
                redis.opsForStream<String, String>().add(stream, mapOf("event" to event.payload))
                jdbc.update(
                    "UPDATE outbox_events SET published_at = now(), publish_attempts = publish_attempts + 1 WHERE event_id = ? AND published_at IS NULL",
                    event.id,
                )
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

    private data class PendingEvent(
        val id: UUID,
        val analysisRunId: UUID,
        val correlationId: UUID,
        val payload: String,
    )

    companion object {
        private val logger = LoggerFactory.getLogger(OutboxPublisher::class.java)
    }
}
