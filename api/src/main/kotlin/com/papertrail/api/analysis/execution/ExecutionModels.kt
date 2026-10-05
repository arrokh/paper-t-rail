package com.papertrail.api.analysis.execution

import java.time.Instant
import java.util.UUID

data class ExecutionSpanHandle(
    val id: UUID,
    val analysisRunId: UUID,
    val operationId: UUID,
    val startedAt: Instant,
    val startedNanos: Long,
    val stageId: String = "source",
    val attempt: Int = 1,
    val eventId: UUID? = null,
)

data class ExecutionSpanSpec(
    val stageId: String,
    val kind: String,
    val name: String,
    val attempt: Int = 1,
    val eventId: UUID? = null,
    val parentSpanId: UUID? = null,
    val providerId: String? = null,
    val modelId: String? = null,
    val attributes: Map<String, Any?> = emptyMap(),
    val operationId: UUID? = null,
    val causationEventId: UUID? = null,
)

data class ExecutionSpanArtifactSpec(
    val role: String,
    val schemaVersion: String,
    val fields: Map<String, Any?>,
)
