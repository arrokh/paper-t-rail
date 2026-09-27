package com.papertrail.api.review.domain

import com.papertrail.api.scholarly.acquisition.domain.TerminalVerificationStatus
import java.time.Instant
import java.util.UUID

data class HumanReview(
    val id: UUID,
    val analysisRunId: UUID,
    val verificationId: UUID,
    val action: HumanReviewAction,
    val overrideStatus: TerminalVerificationStatus?,
    val note: String?,
    val createdAt: Instant,
)
