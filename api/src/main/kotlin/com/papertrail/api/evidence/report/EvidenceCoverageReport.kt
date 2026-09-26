package com.papertrail.api.evidence.report

data class EvidenceCoverageReport(
    val executionStatus: String,
    val verificationPolicyVersion: String?,
    val aggregationPolicyVersion: String?,
    val thresholds: Map<String, Double>?,
    val summary: EvidenceCoverageSummary = EvidenceCoverageSummary(),
    val triageDisclaimer: String = TRIAGE_DISCLAIMER,
) {
    companion object {
        const val TRIAGE_DISCLAIMER =
            "This Evidence Coverage Report is a triage aid, not certification of truth, an academic grade, or an assessment of the whole paper."
    }
}
