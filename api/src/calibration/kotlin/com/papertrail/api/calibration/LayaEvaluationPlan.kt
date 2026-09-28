package com.papertrail.api.calibration

import java.time.Instant

/** Separately versioned, reviewer-approved plan so its hash can pin the dataset without a circular hash. */
data class LayaEvaluationPlan(
    val schemaVersion: Int,
    val planId: String,
    val datasetId: String,
    val datasetVersion: String,
    val datasetSha256: String,
    val heldOutSplitSha256: String,
    val candidate: LayaEvaluationDataset.Candidate,
    val approvedBy: String,
    val approvedAt: String,
    val applicationRevision: String,
    val heldOutCitedPaperIds: Set<String>,
    val uncertaintyMethodId: String,
    val safetyBar: List<SafetyBarCriterion>,
) {
    fun validateAgainst(dataset: LayaEvaluationDataset, actualDatasetSha256: String) {
        dataset.validate()
        require(schemaVersion == CURRENT_SCHEMA_VERSION) { "Unsupported Laya evaluation plan schemaVersion '$schemaVersion'." }
        require(planId.matches(PLAN_ID_PATTERN) && approvedBy.isNotBlank() && isIsoInstant(approvedAt)) {
            "Laya evaluation plan requires an ID, approving reviewer, and ISO-8601 approval time."
        }
        require(dataset.status == LayaEvaluationDataset.DatasetStatus.HUMAN_REVIEWED) {
            "A held-out evaluation plan can only pin a HUMAN_REVIEWED dataset."
        }
        require(datasetId == dataset.datasetId && datasetVersion == dataset.datasetVersion) {
            "Laya evaluation plan dataset ID/version does not match the supplied dataset."
        }
        require(datasetSha256.matches(SHA256_PATTERN) && datasetSha256.equals(actualDatasetSha256, ignoreCase = true)) {
            "Laya evaluation plan dataset SHA-256 does not match the supplied dataset file."
        }
        require(heldOutSplitSha256.matches(SHA256_PATTERN) &&
            heldOutSplitSha256.equals(dataset.heldOutSplitSha256(), ignoreCase = true)
        ) { "Laya evaluation plan held-out split SHA-256 does not match the adjudicated split." }
        require(candidate == dataset.candidate) { "Laya evaluation plan candidate differs from the dataset candidate." }
        require(applicationRevision.matches(GIT_REVISION_PATTERN)) {
            "Laya evaluation plan must pin the 40-character application commit containing the prompt implementation."
        }
        require(uncertaintyMethodId == LayaEvaluationDataset.UNCERTAINTY_METHOD_ID) {
            "Unsupported uncertainty method '$uncertaintyMethodId'."
        }
        require(safetyBar.isNotEmpty()) { "Laya evaluation plan must pre-register a numerical safety bar." }
        require(safetyBar.map(SafetyBarCriterion::metricId).distinct().size == safetyBar.size) {
            "Pre-registered safety-bar metric identifiers must be unique."
        }
        safetyBar.forEach(SafetyBarCriterion::validate)
        val actualHeldOutPaperIds = dataset.cases.filter { it.split == LayaEvaluationDataset.Split.HELD_OUT }
            .map(LayaEvaluationDataset.Case::citedPaperId).toSet()
        require(heldOutCitedPaperIds == actualHeldOutPaperIds) {
            "Pre-registered held-out Cited Paper IDs must exactly match the dataset split."
        }
        require(heldOutCitedPaperIds.all { it.matches(STABLE_IDENTIFIER_PATTERN) }) {
            "Pre-registered held-out Cited Paper IDs must use stable non-text identifiers."
        }
    }

    data class SafetyBarCriterion(
        val metricId: String,
        val comparison: Comparison,
        val threshold: Double,
        val rationale: String,
    ) {
        fun validate() {
            require(metricId.matches(METRIC_ID_PATTERN) && threshold.isFinite() && rationale.isNotBlank()) {
                "Safety-bar criteria require a metric, finite threshold, and rationale."
            }
        }
    }

    enum class Comparison { AT_LEAST, AT_MOST, EXACTLY }

    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
        private val SHA256_PATTERN = Regex("[0-9a-fA-F]{64}")
        private val PLAN_ID_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
        private val STABLE_IDENTIFIER_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
        private val METRIC_ID_PATTERN = Regex("[a-z0-9][a-z0-9._-]{0,191}")
        private val GIT_REVISION_PATTERN = Regex("[0-9a-f]{40}")

        private fun isIsoInstant(value: String): Boolean = try {
            Instant.parse(value)
            true
        } catch (_: Exception) {
            false
        }
    }
}
