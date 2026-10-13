package com.papertrail.api.analysis.successor.http

import com.papertrail.api.analysis.configuration.ExternalProviderConsentSnapshot
import com.papertrail.api.analysis.successor.domain.SuccessorExpectedProviderWork
import com.papertrail.api.analysis.successor.domain.SuccessorRunPlan
import com.papertrail.api.analysis.successor.domain.SuccessorSelectedEntryPlan
import com.papertrail.api.analysis.successor.domain.SuccessorStageDecision
import com.papertrail.api.analysis.successor.domain.SuccessorWorkDisposition
import io.swagger.v3.oas.annotations.media.Schema
import java.util.UUID

@Schema(description = "Advisory, read-only plan for a successor Analysis Run. Submission recomputes this plan under locks.")
data class SuccessorRunPreviewResponse(
    @field:Schema(description = "Recovery Batch being previewed.", format = "uuid")
    val batchId: UUID,
    @field:Schema(description = "Immutable predecessor Analysis Run.", format = "uuid")
    val predecessorAnalysisRunId: UUID,
    @field:Schema(description = "Revision observed while building this plan.", minimum = "0")
    val revision: Long,
    @field:Schema(description = "Stable SHA-256 fingerprint of the normalized plan inputs and decisions.", pattern = "^[0-9a-f]{64}$")
    val planFingerprint: String,
    @field:Schema(description = "True only when this plan can be submitted by the currently supported successor slice.")
    val eligible: Boolean,
    @field:Schema(description = "Selected exact assets and the stage decisions for their entries.")
    val selectedEntries: List<SuccessorEntryPlanResponse>,
    @field:Schema(description = "Unaffected predecessor reference keys whose coverage will be retained.")
    val retainedReferenceKeys: List<String>,
    @field:Schema(description = "Explicit disposition for all unselected bibliography coverage.")
    val retainedDecision: SuccessorStageDecisionResponse,
    @field:Schema(description = "Stage-level reasons that prevent direct reuse or require broader reprocessing.")
    val invalidationReasons: List<String>,
    @field:Schema(description = "Current blockers that prevent submission, if any.")
    val blockers: List<String>,
    @field:Schema(description = "Provider work expected after submission; preview itself makes no provider calls.")
    val expectedProviderWork: List<SuccessorExpectedProviderWorkResponse>,
    @field:Schema(description = "Exact provider consent snapshots required for the selected successor configuration.")
    val requiredConsents: List<ExternalProviderConsentSnapshot>,
)

@Schema(description = "Planned processing for one exact, server-selected Recovery Upload.")
data class SuccessorEntryPlanResponse(
    @field:Schema(description = "Predecessor bibliography-entry identifier; the successor will receive a fresh identifier.", format = "uuid")
    val predecessorBibliographyEntryId: UUID,
    @field:Schema(description = "Run-scoped bibliography key.")
    val localReferenceKey: String,
    @field:Schema(description = "Selected Recovery Upload identifier.", format = "uuid")
    val uploadId: UUID,
    @field:Schema(description = "Exact finalized asset SHA-256.", pattern = "^[0-9a-f]{64}$")
    val contentSha256: String,
    @field:Schema(description = "A DOI extracted from this exact upload and available for a human-confirmed unresolved-identity retry; null when no such assistance is available.", nullable = true)
    val assistedDoi: String?,
    @field:Schema(description = "Fresh same-run processing decisions for the selected entry.")
    val stages: List<SuccessorStageDecisionResponse>,
    @field:Schema(description = "Entry-specific blockers, if any.")
    val blockers: List<String>,
)

@Schema(description = "Disposition of a successor processing stage.")
data class SuccessorStageDecisionResponse(
    @field:Schema(description = "Stable pipeline stage identifier.")
    val stage: String,
    val disposition: SuccessorWorkDisposition,
    @field:Schema(description = "Reason for the REUSE, REPROCESS, or RETAIN disposition; invalidation reasons are returned separately.")
    val reasonCode: String,
)

@Schema(description = "Provider role expected to run after an eligible successor is submitted.")
data class SuccessorExpectedProviderWorkResponse(
    val stage: String,
    val providerId: String,
    val trustBoundary: String,
    val dataCategories: List<String>,
    val retentionDisclosure: String?,
)

fun SuccessorRunPlan.toResponse(): SuccessorRunPreviewResponse = SuccessorRunPreviewResponse(
    batchId = batchId,
    predecessorAnalysisRunId = predecessorAnalysisRunId,
    revision = revision,
    planFingerprint = planFingerprint,
    eligible = eligible,
    selectedEntries = selectedEntries.map(SuccessorSelectedEntryPlan::toResponse),
    retainedReferenceKeys = retainedReferenceKeys,
    retainedDecision = retainedDecision.toResponse(),
    invalidationReasons = invalidationReasons,
    blockers = blockers,
    expectedProviderWork = expectedProviderWork.map(SuccessorExpectedProviderWork::toResponse),
    requiredConsents = requiredConsents,
)

private fun SuccessorSelectedEntryPlan.toResponse() = SuccessorEntryPlanResponse(
    predecessorBibliographyEntryId = predecessorBibliographyEntryId,
    localReferenceKey = localReferenceKey,
    uploadId = uploadId,
    contentSha256 = contentSha256,
    assistedDoi = assistedDoi,
    stages = stages.map(SuccessorStageDecision::toResponse),
    blockers = blockers,
)

private fun SuccessorStageDecision.toResponse() = SuccessorStageDecisionResponse(
    stage = stage,
    disposition = disposition,
    reasonCode = reasonCode,
)

private fun SuccessorExpectedProviderWork.toResponse() = SuccessorExpectedProviderWorkResponse(
    stage = stage,
    providerId = providerId,
    trustBoundary = trustBoundary,
    dataCategories = dataCategories,
    retentionDisclosure = retentionDisclosure,
)
