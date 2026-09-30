package com.papertrail.api.analysis.http

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "One page of Analysis Runs ordered by creation time descending, then ID descending. Cursors support forward and backward navigation.")
data class AnalysisRunPage(
    @field:Schema(description = "Analysis Runs in this page.")
    val items: List<AnalysisRunSummary>,
    @field:Schema(description = "Opaque cursor for the next, older page; null when there are no more runs.")
    val nextCursor: String?,
    @field:Schema(description = "Opaque cursor for the previous, newer page; null when already at the newest page.")
    val previousCursor: String? = null,
)
