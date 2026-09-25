package com.papertrail.api.infrastructure.messaging.events

import java.time.Instant
import java.util.UUID

data class PipelineEvent<T>(
    val eventId: UUID,
    val eventType: String,
    val schemaVersion: Int,
    val analysisRunId: UUID,
    val correlationId: UUID,
    val causationId: UUID?,
    val occurredAt: Instant,
    val attempt: Int,
    val payload: T,
)
