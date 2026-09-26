package com.papertrail.api.evidence.verification.provider

import com.papertrail.api.evidence.verification.domain.SemanticJudgementRequest
import com.papertrail.api.evidence.verification.domain.SemanticJudgementResult

interface SystemOneProvider {
    val providerId: String
    val version: String
    val modelId: String?

    fun evaluate(request: SemanticJudgementRequest): SemanticJudgementResult
}
