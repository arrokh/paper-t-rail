package com.papertrail.api.review.service

import com.papertrail.api.review.domain.HumanReview
import com.papertrail.api.review.domain.HumanReviewAction
import com.papertrail.api.review.repository.HumanReviewRepository
import com.papertrail.api.scholarly.acquisition.domain.TerminalVerificationStatus
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

@Service
class HumanReviewService(
    private val repository: HumanReviewRepository,
) {
    fun record(
        verificationId: UUID,
        action: HumanReviewAction,
        overrideStatus: TerminalVerificationStatus?,
        note: String?,
    ): HumanReview {
        validateReview(action, overrideStatus, note)
        val target = repository.target(verificationId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Claim–Paper Verification not found.")
        if (!target.hasFinalResult) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "A Human Review can only assess a completed machine result.")
        }

        return repository.save(
            analysisRunId = target.analysisRunId,
            verificationId = verificationId,
            action = action,
            overrideStatus = overrideStatus,
            note = note,
        )
    }

    private fun validateReview(
        action: HumanReviewAction,
        overrideStatus: TerminalVerificationStatus?,
        note: String?,
    ) {
        if ((action == HumanReviewAction.OVERRIDE) != (overrideStatus != null)) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "OVERRIDE reviews require overrideStatus; AGREE and DISAGREE reviews must omit it.",
            )
        }
        if (note != null && note.length > MAX_NOTE_LENGTH) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "A Human Review note must not exceed $MAX_NOTE_LENGTH characters.")
        }
    }

    private companion object {
        const val MAX_NOTE_LENGTH = 2_000
    }
}
