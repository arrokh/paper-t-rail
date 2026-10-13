package com.papertrail.api.analysis.successor.queue

import com.papertrail.api.infrastructure.messaging.events.PipelineEvent
import com.papertrail.api.infrastructure.messaging.repository.OutboxRepository
import com.papertrail.api.scholarly.references.events.REFERENCE_RESOLUTION_REQUESTED
import com.papertrail.api.scholarly.references.events.ReferenceResolutionRequestedPayload
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

@Component
class SuccessorRunResolutionQueue(
    private val outboxRepository: OutboxRepository,
) {
    fun enqueue(
        analysisRunId: UUID,
        bibliographyEntryId: UUID,
        documentId: UUID,
        sourceContentSha256: String,
        correlationId: UUID,
        causationId: UUID,
        traceparent: String,
    ) {
        outboxRepository.insert(
            PipelineEvent(
                eventId = UUID.randomUUID(),
                eventType = REFERENCE_RESOLUTION_REQUESTED,
                schemaVersion = 1,
                analysisRunId = analysisRunId,
                correlationId = correlationId,
                causationId = causationId,
                occurredAt = Instant.now(),
                attempt = 0,
                payload = ReferenceResolutionRequestedPayload(
                    documentId = documentId,
                    sourceContentSha256 = sourceContentSha256,
                    bibliographyEntryId = bibliographyEntryId,
                ),
                traceparent = traceparent,
            ),
        )
    }
}
