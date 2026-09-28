package com.papertrail.api.evidence.report

import io.swagger.v3.oas.annotations.media.Schema
import java.util.UUID

/** A diagnostic span whose offsets are zero-based, end-exclusive UTF-16 indexes within its original Evidence Passage. */
data class EvidencePassageSpanReport(
    @field:Schema(description = "Stable identity of this diagnostic child span.")
    val id: UUID,
    @field:Schema(description = "Sentence-span order within the original Evidence Passage, starting at zero.")
    val spanIndex: Int,
    @field:Schema(description = "Core evidence start offset within the original Evidence Passage text.")
    val coreStartOffset: Int,
    @field:Schema(description = "End-exclusive core evidence offset within the original Evidence Passage text.")
    val coreEndOffset: Int,
    @field:Schema(description = "Start offset of the judged text window, including optional adjacent-sentence context.")
    val contextStartOffset: Int,
    @field:Schema(description = "End-exclusive offset of the judged text window, including optional adjacent-sentence context.")
    val contextEndOffset: Int,
    @field:Schema(description = "Exact core sentence text selected for this diagnostic span.")
    val coreText: String,
    @field:Schema(description = "Exact evidence text submitted to Laya, including any recorded adjacent-sentence context.")
    val contextText: String,
    @field:Schema(description = "Pinned-tokenizer counts for each of the six complete System One question sequences; each inferred request must be at most 1,024 tokens.")
    val tokenCounts: List<Int>,
    @field:Schema(description = "Diagnostic work state: PENDING, COMPLETED, FAILED, or INCOMPLETE. A completed span is not a parent Evidence Judgement or final Claim–Paper status.")
    val status: String,
    @field:Schema(description = "Content-free reason code when this required diagnostic span failed or could not fit without truncation.")
    val failureReason: String?,
    @field:Schema(description = "Pinned local System One provider identity.")
    val providerId: String,
    @field:Schema(description = "Pinned local System One model identity.")
    val modelId: String,
    @field:Schema(description = "Pinned local System One runtime and output-mapping version.")
    val providerVersion: String,
    @field:Schema(description = "Evidence Judgement rubric version used to interpret the diagnostic scores.")
    val judgementRubricVersion: String,
    @field:Schema(description = "Versioned deterministic sentence-splitting, core-range, and context-overlap policy.")
    val splittingPolicyVersion: String,
    @field:Schema(description = "Diagnostic-only judgement for this span; never rolled up into its parent Evidence Passage or final Claim–Paper status.")
    val evidenceJudgement: EvidenceJudgementReport?,
)
