package com.papertrail.api.scholarly.acquisition.queue

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.papertrail.api.analysis.service.AnalysisRunStageCompletionService
import com.papertrail.api.evidence.queue.CitedPaperIndexingQueue
import com.papertrail.api.evidence.verification.repository.ClaimReferenceVerificationRepository
import com.papertrail.api.infrastructure.messaging.events.PipelineEvent
import com.papertrail.api.scholarly.acquisition.service.CitedPaperAccessService
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@Component
class CitedPaperAcquisitionRequestedHandler(
    private val jdbc: JdbcTemplate,
    private val transactionTemplate: TransactionTemplate,
    private val objectMapper: ObjectMapper,
    private val citedPaperAccessService: CitedPaperAccessService,
    private val citedPaperIndexingQueue: CitedPaperIndexingQueue,
    private val claimReferenceVerificationRepository: ClaimReferenceVerificationRepository,
    private val analysisRunStageCompletionService: AnalysisRunStageCompletionService,
) {
    fun isProcessed(eventId: UUID): Boolean = jdbc.queryForObject(
        "SELECT EXISTS (SELECT 1 FROM inbox_events WHERE event_id = ?)",
        Boolean::class.java,
        eventId,
    ) == true

    fun handle(serializedEvent: String): UUID {
        val event: PipelineEvent<CitedPaperAcquisitionRequestedPayload> = objectMapper.readValue(serializedEvent)
        require(event.eventType == CITED_PAPER_ACQUISITION_REQUESTED) { "Unsupported event type '${event.eventType}'." }
        if (isProcessed(event.eventId)) return event.eventId

        val run = jdbc.query(
            "SELECT document_id, source_content_sha256, status FROM analysis_runs WHERE id = ?",
            { rs, _ ->
                RunProvenance(
                    documentId = rs.getObject("document_id", UUID::class.java),
                    sourceHash = rs.getString("source_content_sha256"),
                    status = rs.getString("status"),
                )
            },
            event.analysisRunId,
        ).firstOrNull() ?: throw IllegalStateException("Queued Analysis Run is missing.")

        if (run.documentId != event.payload.documentId || run.sourceHash != event.payload.sourceContentSha256) {
            throw IllegalStateException("Cited-paper acquisition event provenance does not match its Analysis Run.")
        }
        if (run.status != "PROCESSING") throw IllegalStateException("Analysis Run is not accepting cited-paper access work.")

        citedPaperAccessService.acquire(event.analysisRunId, event.payload.bibliographyEntryId)
        recordProcessed(event)
        return event.eventId
    }

    fun markFailed(event: PipelineEvent<CitedPaperAcquisitionRequestedPayload>, reason: String) {
        require(event.eventType == CITED_PAPER_ACQUISITION_REQUESTED) { "Unsupported event type '${event.eventType}'." }
        recordProcessed(event, reason)
    }

    private fun recordProcessed(
        event: PipelineEvent<CitedPaperAcquisitionRequestedPayload>,
        failureReason: String? = null,
    ) {
        transactionTemplate.executeWithoutResult {
            val inserted = jdbc.update(
                "INSERT INTO inbox_events (event_id, handler_name, processed_at) VALUES (?, ?, ?) ON CONFLICT (event_id) DO NOTHING",
                event.eventId,
                CITED_PAPER_ACQUISITION_HANDLER,
                Timestamp.from(Instant.now()),
            )
            if (inserted == 0) return@executeWithoutResult
            if (failureReason != null) {
                claimReferenceVerificationRepository.failReference(
                    event.analysisRunId,
                    event.payload.bibliographyEntryId,
                    CITED_PAPER_ACCESS_RETRIES_EXHAUSTED,
                )
                jdbc.update(
                    "UPDATE analysis_runs SET failure_reason = COALESCE(failure_reason, ?), updated_at = now() WHERE id = ? AND status = 'PROCESSING'",
                    failureReason,
                    event.analysisRunId,
                )
            } else {
                citedPaperIndexingQueue.enqueueIfEligible(
                    analysisRunId = event.analysisRunId,
                    bibliographyEntryId = event.payload.bibliographyEntryId,
                    documentId = event.payload.documentId,
                    sourceContentSha256 = event.payload.sourceContentSha256,
                    correlationId = event.correlationId,
                    causationId = event.eventId,
                )
            }
            analysisRunStageCompletionService.completeParsedStageIfReady(event.analysisRunId)
        }
    }

    companion object {
        const val CITED_PAPER_ACCESS_RETRIES_EXHAUSTED = "CITED_PAPER_ACCESS_RETRIES_EXHAUSTED"
    }

    private data class RunProvenance(
        val documentId: UUID,
        val sourceHash: String,
        val status: String,
    )
}
