package com.papertrail.api.evidence.domain

import java.util.UUID

data class EvidenceClaim(
    val verificationId: UUID,
    val atomicClaimId: UUID,
    val text: String,
)
