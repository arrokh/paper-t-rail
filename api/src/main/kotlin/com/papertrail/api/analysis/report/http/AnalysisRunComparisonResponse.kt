package com.papertrail.api.analysis.report.http

import com.fasterxml.jackson.databind.JsonNode
import com.papertrail.api.analysis.http.AnalysisRunPipelineProgress
import com.papertrail.api.review.domain.HumanReview
import io.swagger.v3.oas.annotations.media.ArraySchema
import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant
import java.util.UUID

@Schema(description = "Read-only comparison of persisted Analysis Run reports. Human Reviews remain nested under their originating run and are excluded from machine-result alignment and change counts.")
data class AnalysisRunComparisonResponse(
    val alignmentPolicy: AlignmentPolicy,
    val sourceContentDiffers: Boolean,
    val sourceDocumentDiffers: Boolean,
    val analysisConfigurationDiffers: Boolean,
    val leftRun: Run,
    val rightRun: Run,
    val counts: Counts,
    val items: List<Item>,
    @field:Schema(description = "The Evidence Coverage Report is a triage aid, not truth certification or an assessment of the whole paper. Model confidence is not calibrated.")
    val disclaimer: String = TRIAGE_DISCLAIMER,
) {
    data class AlignmentPolicy(
        val id: String,
        val version: String,
        val description: String,
    )

    data class Run(
        val analysisRunId: UUID,
        val sourceDocumentId: UUID,
        val filename: String,
        val sourceContentSha256: String,
        @field:Schema(description = "Persisted Analysis Run processing state; distinct from Claim–Paper Verification domain outcomes.")
        val status: String,
        val createdAt: Instant,
        val startedAt: Instant?,
        @field:Schema(description = "Persisted run-level failure or warning detail. It is distinct from Claim–Paper Verification domain status.")
        val failureReason: String?,
        @field:Schema(description = "Persisted worker-stage progress, including warning, incomplete, skipped, and failed work. It is separate from final Claim–Paper Verification statuses.")
        val pipeline: AnalysisRunPipelineProgress?,
        val referenceResolutionExecutionStatus: String,
        val evidenceCoverageExecutionStatus: String,
        @field:Schema(description = "Exact immutable configuration and provenance snapshot pinned to this Analysis Run.")
        val configuration: JsonNode,
    )

    data class Counts(
        val unchanged: Int,
        val changed: Int,
        val added: Int,
        val removed: Int,
        @field:Schema(description = "Items retained separately because no compatible reference identity exists or alignment has multiple candidates.")
        val unmatched: Int,
        @field:Schema(description = "Aligned pairs whose machine processing state or final domain status differs. Human Reviews are not included.")
        val outcomeChanges: Int,
    )

    data class Item(
        @field:Schema(description = "UNCHANGED, CHANGED, ADDED, REMOVED, or UNMATCHED.", allowableValues = ["UNCHANGED", "CHANGED", "ADDED", "REMOVED", "UNMATCHED"])
        val changeKind: String,
        @field:Schema(description = "ALIGNED for a matched pair, UNPAIRED for a definite addition or removal, and UNMATCHED when identity is unavailable or alignment is ambiguous.", allowableValues = ["ALIGNED", "UNPAIRED", "UNMATCHED"])
        val alignmentStatus: String,
        @field:Schema(
            description = "Reason an item stays unmatched; null for aligned, added, or removed items.",
            allowableValues = ["AMBIGUOUS_CANDIDATES", "NO_COMPATIBLE_REFERENCE_IDENTITY", "COUNTERPART_REFERENCE_IDENTITY_UNAVAILABLE"],
        )
        val unmatchedReason: String?,
        @field:ArraySchema(
            arraySchema = Schema(description = "Changed machine-result categories. Review changes are deliberately excluded."),
            schema = Schema(allowableValues = ["sourceClaimAndContext", "citationTargets", "referenceResolution", "citedPaperAccess", "evidencePassages", "machineOutcome"]),
        )
        val changedFields: List<String>,
        val outcomeChanged: Boolean,
        val left: PairResult?,
        val right: PairResult?,
    )

    data class PairResult(
        val atomicClaimId: UUID,
        val claimText: String,
        val sourceStartOffset: Int,
        val sourceEndOffset: Int,
        val citationContextText: String,
        val citationTargets: List<CitationTarget>,
        val reference: Reference,
        val citedPaperAccess: CitedPaperAccess?,
        val evidencePassages: List<EvidencePassage>,
        @field:Schema(description = "Processing status is separate from finalStatus. INCOMPLETE items have no final domain status.", allowableValues = ["PENDING", "COMPLETED", "INCOMPLETE"])
        val processingStatus: String,
        val processingFailureReason: String?,
        val finalStatus: String?,
        val verificationScope: String?,
        val terminalReason: String?,
        val evidenceConflict: Boolean?,
        val aggregatorVersion: String?,
        @field:Schema(description = "Append-only reviews attached to this Verification in this Analysis Run. Reviews are not copied or applied to the other run and do not participate in machine comparison.")
        val humanReviews: List<HumanReview>,
    )

    data class CitationTarget(
        val markerText: String,
        val associationKind: String,
    )

    data class Reference(
        val localReferenceKey: String,
        val title: String?,
        val rawText: String,
        val authors: List<String>,
        val year: Int?,
        val doi: String?,
        val referenceType: String,
        val resolutionStatus: String,
        val resolutionReasonCode: String?,
        val matchMethod: String?,
        val canonicalPaper: CanonicalPaper?,
    )

    data class CanonicalPaper(
        val id: UUID,
        val doi: String?,
        val title: String,
        val authors: List<String>,
        val year: Int?,
    )

    data class CitedPaperAccess(
        val accessStatus: String,
        val accessOrigin: String,
        val accessReason: String?,
        val accessReasons: List<String>,
        val accessProgressStatus: String?,
        val accessProgressReason: String?,
        val indexingStatus: String?,
        val sourceAssetId: UUID?,
        val contentSha256: String?,
        val parserProvider: String?,
        val parserVersion: String?,
        val language: String?,
        val languageDetectorVersion: String?,
    )

    data class EvidencePassage(
        val text: String,
        val sectionOrder: Int,
        val sectionHeading: String?,
        val paragraphStart: Int,
        val paragraphEnd: Int,
        val pageNumber: Int?,
        @field:Schema(description = "Reliable one-based physical PDF pages for this Evidence Passage in its exact stored Cited Paper Asset. Empty when parser provenance is unavailable or ambiguous.")
        val pageNumbers: List<Int>,
        val vectorRank: Int?,
        val lexicalRank: Int?,
        val fusedRank: Int,
        val sourceAssetId: UUID,
        val contentSha256: String,
        val parserProvider: String,
        val parserVersion: String,
        val language: String,
        val languageDetectorVersion: String,
        val judgement: String?,
        val evidenceRole: String?,
    )

    companion object {
        const val TRIAGE_DISCLAIMER = "This read-only comparison is a triage aid, not truth certification or an assessment of the whole paper. Machine confidence is not calibrated; no run is described as better. Human Reviews remain with their original run."
    }
}
