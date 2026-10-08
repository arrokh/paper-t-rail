package com.papertrail.api.scholarly.references.report

import com.papertrail.api.evidence.report.CitedReferenceVerificationOutcome
import com.papertrail.api.scholarly.acquisition.report.CitedPaperAccessReport
import io.swagger.v3.oas.annotations.media.Schema

data class BibliographyResolutionReportEntry(
    val entryOrder: Int,
    val localReferenceKey: String,
    val rawText: String,
    val title: String?,
    val authors: List<String>,
    val year: Int?,
    val doi: String?,
    val referenceType: String,
    val status: String,
    val reasonCode: String?,
    val canonicalPaper: ReportCanonicalPaper?,
    val confidenceScore: Double?,
    val matchMethod: String?,
    @field:Schema(
        description = "Persisted access-stage state for this Bibliography Entry; null when legacy history has no saved stage item.",
        allowableValues = ["WAITING", "IN_PROGRESS", "COMPLETED", "SKIPPED", "FAILED"],
    )
    val accessProgressStatus: String? = null,
    @field:Schema(
        description = "Persisted content-free access-stage reason. Null does not imply a particular historical cause.",
        allowableValues = [
            "ACCESS_PATH_NOT_CONFIGURED", "ACCESS_SKIPPED_IDENTITY_UNRESOLVED", "ACCESS_SKIPPED_UNSUPPORTED_REFERENCE_TYPE",
            "REFERENCE_NOT_ELIGIBLE_FOR_ACCESS", "REFERENCE_RESOLUTION_RETRIES_EXHAUSTED", "CITED_PAPER_ACCESS_RETRIES_EXHAUSTED",
        ],
    )
    val accessProgressReason: String? = null,
    val citedPaperAccess: CitedPaperAccessReport? = null,
    val verificationOutcomes: List<CitedReferenceVerificationOutcome> = emptyList(),
)
