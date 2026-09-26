package com.papertrail.api.scholarly.references.report

import com.papertrail.api.evidence.report.EvidenceCoverageReport
import java.util.UUID

data class ReferenceResolutionReportResponse(
    val analysisRunId: UUID,
    val runStatus: String,
    val referenceResolution: ReferenceResolutionReport,
    val evidenceCoverage: EvidenceCoverageReport = EvidenceCoverageReport(
        executionStatus = "NOT_RUN",
        verificationPolicyVersion = null,
        aggregationPolicyVersion = null,
        thresholds = null,
    ),
)
