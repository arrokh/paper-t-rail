package com.papertrail.api.scholarly.references.attempts.domain

sealed interface ReferenceResolutionAttemptStartResult {
    data object Started : ReferenceResolutionAttemptStartResult

    data class NoSend(
        val reason: ReferenceResolutionAttemptNoSendReason,
    ) : ReferenceResolutionAttemptStartResult
}
