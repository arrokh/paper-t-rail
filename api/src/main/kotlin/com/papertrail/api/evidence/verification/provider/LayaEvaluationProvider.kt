package com.papertrail.api.evidence.verification.provider

import com.papertrail.api.evidence.verification.domain.SemanticJudgementRequest
import com.papertrail.api.evidence.verification.domain.SemanticJudgementResult
import java.util.UUID

/** Explicit evaluation-only capture hook; ordinary System One calls never retain raw provider responses. */
interface LayaEvaluationProvider : SystemOneProvider {
    /** Called by the isolated calibration source set, not by Analysis Run processing. */
    fun evaluateForCalibration(request: SemanticJudgementRequest): Evaluation

    data class Evaluation(
        val result: SemanticJudgementResult,
        val rawResponseBytesByPassageId: Map<UUID, ByteArray>,
        val tokenUsageByPassageId: Map<UUID, TokenUsage>,
    )

    data class TokenUsage(
        val inputTokens: Long,
        val outputTokens: Long,
    )
}
