package com.papertrail.api.analysis.http

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "One page of Analysis Runs ordered by creation time descending, then ID descending.")
data class AnalysisRunPage(
    @field:Schema(description = "Analysis Runs in this page.")
    val items: List<AnalysisRunSummary>,
    @field:Schema(description = "Opaque cursor for the next, older page; null when there are no more runs.")
    val nextCursor: String?,
)
