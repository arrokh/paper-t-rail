package com.papertrail.api.analysis.execution

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "One cursor page of execution spans, ordered by start time then span ID ascending.")
data class ExecutionSpanPage(
    val items: List<ExecutionSpanResponse>,
    @field:Schema(description = "Opaque forward cursor, or null when there are no later spans.")
    val nextCursor: String?,
)
