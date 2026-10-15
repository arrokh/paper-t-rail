package com.papertrail.api.scholarly.references.attempts.domain

sealed interface ReferenceResolutionAttemptResult {
    data class Reserved(
        val reservation: ReferenceResolutionAttemptReservation,
    ) : ReferenceResolutionAttemptResult

    data class NoSend(
        val reason: ReferenceResolutionAttemptNoSendReason,
        val reservation: ReferenceResolutionAttemptReservation? = null,
    ) : ReferenceResolutionAttemptResult
}
