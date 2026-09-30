package com.papertrail.api.analysis.http

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "Execution progress for the Analysis Run's persisted worker pipeline.")
data class AnalysisRunPipelineProgress(
    @field:Schema(description = "Worker stages in execution order. The Evidence Coverage Report is a read model, not a worker stage.")
    val stages: List<Stage>,
) {
    data class Stage(
        @field:Schema(description = "Stable stage identifier.", example = "references")
        val id: String,
        @field:Schema(
            description = "Aggregate execution state for this stage.",
            allowableValues = ["WAITING", "IN_PROGRESS", "COMPLETED", "COMPLETED_WITH_WARNINGS", "SKIPPED", "FAILED"],
        )
        val status: String,
        @field:Schema(description = "Counts of persisted work items by execution state.")
        val counts: Counts,
        @field:Schema(description = "Named worker operations and their persisted work items.")
        val steps: List<Step>,
    )

    data class Step(
        @field:Schema(description = "Stable worker operation identifier.", example = "resolve-entry")
        val id: String,
        @field:Schema(
            description = "Aggregate execution state for this worker operation.",
            allowableValues = ["WAITING", "IN_PROGRESS", "COMPLETED", "COMPLETED_WITH_WARNINGS", "SKIPPED", "FAILED"],
        )
        val status: String,
        @field:Schema(description = "Counts of persisted work items by execution state.")
        val counts: Counts,
        @field:Schema(description = "Persisted work item states and non-content failure or skip reason codes.")
        val items: List<Item>,
    )

    data class Item(
        @field:Schema(description = "Stable work item identifier.")
        val id: String,
        @field:Schema(description = "Short item label. Source claim and evidence text are not included.")
        val label: String,
        @field:Schema(
            description = "Current execution state of this work item.",
            allowableValues = ["WAITING", "IN_PROGRESS", "COMPLETED", "SKIPPED", "FAILED"],
        )
        val status: String,
        @field:Schema(description = "Stable, non-content reason code for skipped or failed work; null otherwise.")
        val reasonCode: String?,
    )

    data class Counts(
        @field:Schema(description = "Total persisted work items.", example = "12")
        val total: Int,
        @field:Schema(description = "Items whose work has not started.")
        val waiting: Int,
        @field:Schema(description = "Items currently being processed by a worker.")
        val inProgress: Int,
        @field:Schema(description = "Items whose worker operation completed.")
        val completed: Int,
        @field:Schema(description = "Items that did not apply because a prerequisite or configuration was absent.")
        val skipped: Int,
        @field:Schema(description = "Items whose worker operation ended in a processing failure.")
        val failed: Int,
    )
}
