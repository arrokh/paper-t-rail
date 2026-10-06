package com.papertrail.api.analysis.execution.http

import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant
import java.util.UUID

@Schema(description = "Run-level availability, capture choice, and elapsed interval for inspectable execution.")
data class AnalysisRunExecutionSummary(
    val analysisRunId: UUID,
    @field:Schema(description = "Whether sanitized payload capture was requested when the Analysis Run was created; null when execution recording is unavailable.")
    val captureRequested: Boolean?,
    @field:Schema(description = "Whether payload capture remains enabled for this recording; recordingState separately indicates whether the run can still record operations.")
    val captureEnabled: Boolean,
    @field:Schema(allowableValues = ["RECORDING", "STOPPED", "NOT_RECORDED"], description = "Explicitly distinguishes legacy history from stopped capture.")
    val recordingState: String,
    @field:Schema(allowableValues = ["COMPLETE", "INCOMPLETE", "RECORDING", "NOT_RECORDED"])
    val completeness: String,
    val startedAt: Instant?,
    val finishedAt: Instant?,
    @field:Schema(description = "Elapsed time from recording start to finish or now; overlapping spans are not summed.")
    val totalDurationMillis: Long?,
    @field:Schema(
        allowableValues = [
            "UNSAFE_SPAN_METADATA_OMITTED", "UNSAFE_SPAN_RESULT_OMITTED", "INTERVAL_TIMESTAMPS_UNAVAILABLE",
            "SPAN_STORAGE_UNAVAILABLE", "RETRY_SCHEDULE_TIMESTAMPS_UNAVAILABLE", "QUEUE_ENQUEUE_TIMESTAMP_UNAVAILABLE",
            "UNSAFE_ARTIFACT_METADATA_OMITTED", "ARTIFACT_STORAGE_UNAVAILABLE", "UNSAFE_SPAN_ATTRIBUTES_OMITTED",
            "CAPTURE_STOPPED", "INTERRUPTED_OPERATION",
        ],
        description = "First safe reason recorded for an execution trace gap; null when no gap reason was recorded.",
    )
    val gapReason: String? = null,
)
