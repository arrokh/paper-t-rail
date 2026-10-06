package com.papertrail.api.infrastructure.messaging.repository

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@Repository
class InboxRepository(
    private val jdbc: JdbcTemplate,
) {
    fun isProcessed(eventId: UUID): Boolean = jdbc.queryForObject(
        "SELECT EXISTS (SELECT 1 FROM inbox_events WHERE event_id = ?)",
        Boolean::class.java,
        eventId,
    ) == true

    fun insertIfAbsent(eventId: UUID, analysisRunId: UUID, handlerName: String, processedAt: Instant): Boolean =
        jdbc.update(
            "INSERT INTO inbox_events (event_id, analysis_run_id, handler_name, processed_at) VALUES (?, ?, ?, ?) ON CONFLICT (event_id) DO NOTHING",
            eventId,
            analysisRunId,
            handlerName,
            Timestamp.from(processedAt),
        ) == 1

    fun insert(eventId: UUID, analysisRunId: UUID, handlerName: String, processedAt: Instant): Boolean =
        jdbc.update(
            "INSERT INTO inbox_events (event_id, analysis_run_id, handler_name, processed_at) VALUES (?, ?, ?, ?)",
            eventId,
            analysisRunId,
            handlerName,
            Timestamp.from(processedAt),
        ) == 1
}
