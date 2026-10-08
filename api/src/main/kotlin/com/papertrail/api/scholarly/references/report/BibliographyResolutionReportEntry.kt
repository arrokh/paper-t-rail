package com.papertrail.api.scholarly.references.report

import com.papertrail.api.citation.parsing.ParsedBibliographyIdentifier
import com.papertrail.api.citation.parsing.ParsedBibliographySourceLocation
import com.papertrail.api.evidence.report.CitedReferenceVerificationOutcome
import com.papertrail.api.scholarly.acquisition.report.CitedPaperAccessReport
import com.papertrail.api.scholarly.references.resolver.ScholarlyCandidateEvidence
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
    @field:Schema(description = "Up to three provider-candidate comparisons with component reasons; consult status and matchMethod for the identity decision. Scores are rankings, not probabilities.")
    val candidateEvidence: List<ScholarlyCandidateEvidence> = emptyList(),
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
    @field:Schema(description = "Unnormalized element text content extracted from GROBID TEI; raw TEI bytes are stored separately.")
    val sourceTextContent: String? = null,
    @field:Schema(description = "GROBID bibliography element, or null when source provenance was not captured.")
    val sourceElement: String? = null,
    @field:Schema(description = "Original TEI xml:id; null means no source ID was captured or the entry used a generated internal key.")
    val sourceLocalReferenceKey: String? = null,
    @field:Schema(description = "Origin of localReferenceKey. UNKNOWN is used when legacy provenance is unavailable.", allowableValues = ["GROBID_XML_ID", "GENERATED_FALLBACK", "UNKNOWN"])
    val localReferenceKeyOrigin: String = "UNKNOWN",
    @field:Schema(description = "Raw TEI identifiers and URL targets plus local normalization; retaining these values does not trigger provider requests.")
    val identifiers: List<ParsedBibliographyIdentifier> = emptyList(),
    @field:Schema(description = "GROBID page/coordinate metadata. Coordinates are not normalized source-text spans.")
    val sourceLocations: List<ParsedBibliographySourceLocation> = emptyList(),
    @field:Schema(description = "Provisional extraction-artifact signals only; these are not adjudicated truth or a reason to drop this Bibliography Entry.")
    val provisionalArtifactSignals: List<String> = emptyList(),
    @field:Schema(description = "Known extraction limitations or unavailable historical provenance.")
    val extractionLimitations: List<String> = emptyList(),
    @field:Schema(description = "Whether extraction provenance was captured; old rows report UNAVAILABLE rather than inferred cause.", allowableValues = ["CAPTURED", "UNAVAILABLE"])
    val provenanceCaptureStatus: String = "UNAVAILABLE",
)
