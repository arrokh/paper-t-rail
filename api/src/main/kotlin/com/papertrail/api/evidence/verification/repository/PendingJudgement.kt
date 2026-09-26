package com.papertrail.api.evidence.verification.repository

import com.papertrail.api.evidence.verification.domain.SemanticJudgementRequest
import java.util.UUID

data class PendingJudgement(
    val verificationId: UUID,
    val request: SemanticJudgementRequest,
)
