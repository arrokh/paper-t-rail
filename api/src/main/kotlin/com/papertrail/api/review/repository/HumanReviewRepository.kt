package com.papertrail.api.review.repository

import com.papertrail.api.review.domain.HumanReview
import com.papertrail.api.review.domain.HumanReviewAction
import com.papertrail.api.review.domain.HumanReviewTarget
import com.papertrail.api.scholarly.acquisition.domain.TerminalVerificationStatus
import java.util.UUID

interface HumanReviewRepository {
    fun target(verificationId: UUID): HumanReviewTarget?

    fun save(
        analysisRunId: UUID,
        verificationId: UUID,
        action: HumanReviewAction,
        overrideStatus: TerminalVerificationStatus?,
        note: String?,
    ): HumanReview

    fun byRun(analysisRunId: UUID): Map<UUID, List<HumanReview>>
}
