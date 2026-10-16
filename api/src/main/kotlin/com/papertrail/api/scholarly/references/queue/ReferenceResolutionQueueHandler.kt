package com.papertrail.api.scholarly.references.queue

import com.papertrail.api.infrastructure.messaging.PipelineHandlerDisposition
import com.papertrail.api.infrastructure.messaging.PipelineHandlerFailureDisposition
import com.papertrail.api.infrastructure.messaging.PipelineHandlerAttemptPolicy
import com.papertrail.api.infrastructure.messaging.PipelineHandlerAttemptPolicyProvider
import com.papertrail.api.infrastructure.messaging.events.PipelineEvent
import com.papertrail.api.scholarly.references.events.ReferenceResolutionRequestedPayload
import java.util.UUID

interface ReferenceResolutionQueueHandler : PipelineHandlerAttemptPolicyProvider {
    override val attemptPolicy: PipelineHandlerAttemptPolicy
        get() = PipelineHandlerAttemptPolicy.HANDLER_DISPOSITION

    fun isProcessed(eventId: UUID): Boolean

    fun handleWithDisposition(serializedEvent: String): PipelineHandlerDisposition

    fun markFailed(
        event: PipelineEvent<ReferenceResolutionRequestedPayload>,
        reason: String,
    ): PipelineHandlerFailureDisposition
}
