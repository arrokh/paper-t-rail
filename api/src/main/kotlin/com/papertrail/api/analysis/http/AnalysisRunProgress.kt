package com.papertrail.api.analysis.http

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "Persisted stage, optional completion percentage, and user-facing progress message.")
data class AnalysisRunProgress(
    @field:Schema(description = "Current persisted processing stage.")
    val stage: String,
    @field:Schema(description = "Completion percentage when the stage provides one.")
    val percent: Int? = null,
    @field:Schema(description = "Human-readable progress or failure message.")
    val message: String,
)
