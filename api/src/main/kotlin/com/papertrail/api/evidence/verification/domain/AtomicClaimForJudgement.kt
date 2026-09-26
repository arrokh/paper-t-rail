package com.papertrail.api.evidence.verification.domain

import java.util.UUID

data class AtomicClaimForJudgement(
    val id: UUID,
    val text: String,
)
