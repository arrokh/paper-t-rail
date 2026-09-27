package com.papertrail.api.review.http

import com.papertrail.api.review.domain.HumanReviewAction
import com.papertrail.api.scholarly.acquisition.domain.TerminalVerificationStatus
import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "Append-only human assessment of a completed machine Claim–Paper Verification.")
data class CreateHumanReviewRequest(
    @field:Schema(description = "Whether the researcher agrees with, disagrees with, or records a human status for the machine result.")
    val action: HumanReviewAction,
    @field:Schema(description = "Required for OVERRIDE; kept separate from the machine final status.")
    val overrideStatus: TerminalVerificationStatus? = null,
    @field:Schema(description = "Optional researcher note; at most 2,000 characters.", maxLength = 2000)
    val note: String? = null,
)
