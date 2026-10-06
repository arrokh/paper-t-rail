package com.papertrail.api.evidence.queue

import com.papertrail.api.evidence.events.CITED_PAPER_INDEXING_REQUESTED
import com.papertrail.api.evidence.events.CitedPaperIndexingRequestedPayload
import com.papertrail.api.evidence.domain.EmbeddingProfile
import com.papertrail.api.evidence.repository.CitedPaperIndexingRepository
import com.papertrail.api.infrastructure.messaging.events.PipelineEvent
import com.papertrail.api.infrastructure.messaging.repository.OutboxRepository
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

@Component
class CitedPaperIndexingQueue(
    private val repository: CitedPaperIndexingRepository,
    private val outboxRepository: OutboxRepository,
) {
    fun enqueueIfEligible(
        analysisRunId: UUID,
        bibliographyEntryId: UUID,
        documentId: UUID,
        sourceContentSha256: String,
        correlationId: UUID,
        causationId: UUID,
        traceparent: String? = null,
        tracestate: String? = null,
    ): EvidenceIndexingEnqueueResult {
        if (!repository.isEligible(analysisRunId, bibliographyEntryId)) {
            return EvidenceIndexingEnqueueResult.NOT_ELIGIBLE
        }

        val configuration = repository.configuration(analysisRunId)
            ?: throw IllegalStateException("Analysis Run configuration is missing for evidence retrieval.")
        val profile = EmbeddingProfile.forDiagnosis(configuration.embedding, configuration.retrieval.embeddingProfileHash)
        val retrieval = configuration.retrieval
        val indexingStatus = if (profile.dimension > 0) "PENDING" else "FAILED"
        val failureReason = if (profile.dimension > 0) null else "EMBEDDING_PROFILE_UNAVAILABLE"
        val inserted = repository.insertIndexing(
            analysisRunId = analysisRunId,
            bibliographyEntryId = bibliographyEntryId,
            status = indexingStatus,
            failureReason = failureReason,
            retrievalProfileId = retrieval.profileId,
            vectorCandidateLimit = retrieval.vectorCandidateLimit,
            lexicalCandidateLimit = retrieval.lexicalCandidateLimit,
            finalCandidateLimit = retrieval.finalCandidateLimit,
            reciprocalRankFusionConstant = retrieval.reciprocalRankFusionConstant,
            embeddingProvider = profile.providerId,
            embeddingModel = profile.modelId,
            embeddingVersion = profile.version,
            embeddingDimension = profile.dimension,
            embeddingProfileHash = profile.profileHash,
        )
        if (!inserted) {
            return if (repository.status(analysisRunId, bibliographyEntryId) == "FAILED") {
                EvidenceIndexingEnqueueResult.FAILED
            } else {
                EvidenceIndexingEnqueueResult.QUEUED
            }
        }
        if (indexingStatus != "PENDING") return EvidenceIndexingEnqueueResult.FAILED

        val event = PipelineEvent(
            eventId = UUID.randomUUID(),
            eventType = CITED_PAPER_INDEXING_REQUESTED,
            schemaVersion = 1,
            analysisRunId = analysisRunId,
            correlationId = correlationId,
            causationId = causationId,
            occurredAt = Instant.now(),
            attempt = 0,
            payload = CitedPaperIndexingRequestedPayload(documentId, sourceContentSha256, bibliographyEntryId),
            traceparent = traceparent,
            tracestate = tracestate,
        )
        outboxRepository.insert(event)
        return EvidenceIndexingEnqueueResult.QUEUED
    }
}
