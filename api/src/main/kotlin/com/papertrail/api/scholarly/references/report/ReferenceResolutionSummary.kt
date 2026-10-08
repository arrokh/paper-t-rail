package com.papertrail.api.scholarly.references.report

import com.papertrail.api.scholarly.references.resolver.ReferenceResolutionStatus
import io.swagger.v3.oas.annotations.media.Schema

data class ReferenceResolutionSummary(
    val total: Int,
    val resolved: Int,
    val unresolved: Int,
    val unsupportedReferenceType: Int,
    val notAttempted: Int,
    val failed: Int,
    @field:Schema(description = "Number of entries with provisional extraction-pattern signals; these are not human-adjudicated classifications.")
    val provisionalArtifactSignalEntries: Int = 0,
) {
    companion object {
        fun fromEntries(entries: List<BibliographyResolutionReportEntry>) = ReferenceResolutionSummary(
            total = entries.size,
            resolved = entries.count { it.status == ReferenceResolutionStatus.RESOLVED.name },
            unresolved = entries.count { it.status == ReferenceResolutionStatus.UNRESOLVED.name },
            unsupportedReferenceType = entries.count { it.status == ReferenceResolutionStatus.UNSUPPORTED_REFERENCE_TYPE.name },
            notAttempted = entries.count { it.status == "NOT_ATTEMPTED" },
            failed = entries.count { it.status == "RESOLUTION_FAILED" },
            provisionalArtifactSignalEntries = entries.count { it.provisionalArtifactSignals.isNotEmpty() },
        )
    }
}
