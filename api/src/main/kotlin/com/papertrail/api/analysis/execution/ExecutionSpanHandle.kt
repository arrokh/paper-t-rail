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
