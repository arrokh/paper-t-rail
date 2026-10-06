package com.papertrail.api.analysis.execution.domain

import java.util.UUID

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
