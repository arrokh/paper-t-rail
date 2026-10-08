package com.papertrail.api.scholarly.references.report

import com.papertrail.api.citation.parsing.BibliographyNormalizationPolicySelection
import io.swagger.v3.oas.annotations.media.Schema

data class ReferenceResolutionReport(
    val executionStatus: String,
    val scorePolicyVersion: String?,
    val confidenceThreshold: Double?,
    val summary: ReferenceResolutionSummary,
    val entries: List<BibliographyResolutionReportEntry>,
    @field:Schema(description = "Bibliography normalization policy pinned to this run; null means the run predates persisted policy selection.")
    val bibliographyNormalizationPolicy: BibliographyNormalizationPolicySelection? = null,
)
