package com.papertrail.api.review.domain

import java.util.UUID

data class HumanReviewTarget(
    val analysisRunId: UUID,
    val hasFinalResult: Boolean,
)
