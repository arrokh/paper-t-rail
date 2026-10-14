package com.papertrail.api.analysis.successor.http

import com.papertrail.api.analysis.successor.service.SuccessorSubmissionResult
import io.swagger.v3.oas.annotations.media.Schema
import java.util.UUID

@Schema(description = "The immutable successor Analysis Run created by an idempotent Recovery Batch submission.")
data class SuccessorRunSubmissionResponse(
    @field:Schema(description = "Persisted submission identifier.", format = "uuid")
    val submissionId: UUID,
    @field:Schema(description = "New successor Analysis Run identifier.", format = "uuid")
    val successorAnalysisRunId: UUID,
    @field:Schema(description = "Immutable predecessor Analysis Run identifier.", format = "uuid")
    val predecessorAnalysisRunId: UUID,
    @field:Schema(description = "Recovery Batch revision frozen by this submission.", minimum = "0")
    val revision: Long,
    @field:Schema(description = "SHA-256 fingerprint of the accepted preview plan.", pattern = "^[0-9a-f]{64}$")
    val planFingerprint: String,
    @field:Schema(description = "Number of entries reassessed from selected Recovery Uploads; zero for DOI-only identity successors.", minimum = "0")
    val selectedEntryCount: Int,
    @field:Schema(description = "Number of unaffected entries retained in the successor.", minimum = "0")
    val retainedEntryCount: Int,
    @field:Schema(description = "Number of selected entries queued for evidence processing.", minimum = "0")
    val queuedEntryCount: Int,
    @field:Schema(description = "Number of DOI-assisted entries submitted for identity resolution without a PDF asset.", minimum = "0")
    val identifierAssistanceEntryCount: Int,
    @field:Schema(description = "Number of DOI-assisted entries queued for reference resolution, including identity-only work.", minimum = "0")
    val resolutionQueuedEntryCount: Int,
    @field:Schema(description = "True when this response replays an existing submission.")
    val replayed: Boolean,
)

fun SuccessorSubmissionResult.toResponse() = SuccessorRunSubmissionResponse(
    submissionId = submissionId,
    successorAnalysisRunId = successorAnalysisRunId,
    predecessorAnalysisRunId = predecessorAnalysisRunId,
    revision = revision,
    planFingerprint = planFingerprint,
    selectedEntryCount = selectedEntryCount,
    retainedEntryCount = retainedEntryCount,
    queuedEntryCount = queuedEntryCount,
    identifierAssistanceEntryCount = identifierAssistanceEntryCount,
    resolutionQueuedEntryCount = resolutionQueuedEntryCount,
    replayed = replayed,
)
