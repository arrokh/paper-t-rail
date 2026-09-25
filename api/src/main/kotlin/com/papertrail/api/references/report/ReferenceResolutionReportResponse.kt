package com.papertrail.api.references.report

import java.util.UUID

data class ReferenceResolutionReportResponse(
    val analysisRunId: UUID,
    val runStatus: String,
    val referenceResolution: ReferenceResolutionReport,
)
