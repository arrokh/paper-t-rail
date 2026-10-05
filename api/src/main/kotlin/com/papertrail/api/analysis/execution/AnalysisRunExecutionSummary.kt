package com.papertrail.api.analysis.execution

import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant
import java.util.UUID

@Schema(description = "Run-level availability, capture choice, and elapsed interval for inspectable execution.")
data class AnalysisRunExecutionSummary(
    val analysisRunId: UUID,
    @field:Schema(description = "Whether future sanitized artifacts may be captured.")
    val captureEnabled: Boolean,
    @field:Schema(allowableValues = ["RECORDING", "STOPPED", "NOT_RECORDED"], description = "Explicitly distinguishes legacy history from stopped capture.")
    val recordingState: String,
    @field:Schema(allowableValues = ["COMPLETE", "INCOMPLETE", "RECORDING", "NOT_RECORDED"])
    val completeness: String,
    val startedAt: Instant?,
    val finishedAt: Instant?,
    @field:Schema(description = "Elapsed time from recording start to finish or now; overlapping spans are not summed.")
    val totalDurationMillis: Long?,
)
