package com.papertrail.api.infrastructure.messaging.repository

import com.papertrail.api.utils.JsonUtil
import com.papertrail.api.infrastructure.messaging.events.PipelineEvent
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@Repository
class OutboxRepository(
    private val jdbc: JdbcTemplate,
) {
    fun insert(event: PipelineEvent<*>, createdAt: Instant? = null) {
        val serializedEvent = JsonUtil.toJson(event)
        val persistedAt = createdAt ?: Instant.now()
        jdbc.update(
            """
            INSERT INTO outbox_events (
                event_id, event_type, schema_version, analysis_run_id,
                correlation_id, causation_id, occurred_at, payload, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
            """.trimIndent(),
            event.eventId,
            event.eventType,
            event.schemaVersion,
            event.analysisRunId,
            event.correlationId,
            event.causationId,
            Timestamp.from(event.occurredAt),
            serializedEvent,
            Timestamp.from(persistedAt),
        )
    }

    fun pendingEvents(): List<PendingEvent> = jdbc.query(
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

    fun markPublished(eventId: UUID) {
        jdbc.update(
            "UPDATE outbox_events SET published_at = now(), publish_attempts = publish_attempts + 1 WHERE event_id = ? AND published_at IS NULL",
            eventId,
        )
    }

    data class PendingEvent(
        val id: UUID,
        val analysisRunId: UUID,
        val correlationId: UUID,
        val payload: String,
    )
}
