package com.papertrail.api.analysis.recovery.service

import com.papertrail.api.analysis.recovery.domain.RecoverySubmissionsDisabledException
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

@Component
class RecoverySubmissionGate(
    @Value("\${paper-trail.recovery.submissions-enabled}")
    private val submissionsEnabled: Boolean,
) {
    val isEnabled: Boolean
        get() = submissionsEnabled

    fun requireEnabled() {
        if (!submissionsEnabled) {
            throw RecoverySubmissionsDisabledException()
        }
    }
}
