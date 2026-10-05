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
    /** Optional W3C context; older queued envelopes deserialize without these fields. */
    val traceparent: String? = null,
    val tracestate: String? = null,
    /** Worker-populated durable timestamps used only for execution interval recording. */
    val queueWaitStartedAt: Instant? = null,
    val retryScheduledAt: Instant? = null,
    val retryDueAt: Instant? = null,
)
