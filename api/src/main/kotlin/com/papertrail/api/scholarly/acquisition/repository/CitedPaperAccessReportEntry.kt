package com.papertrail.api.scholarly.acquisition.repository

import com.papertrail.api.scholarly.acquisition.report.CitedPaperAccessReport
import java.util.UUID

data class CitedPaperAccessReportEntry(
    val bibliographyEntryId: UUID,
    val localReferenceKey: String,
    val report: CitedPaperAccessReport,
)
