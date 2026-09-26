package com.papertrail.api.evidence.queue

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.papertrail.api.analysis.service.AnalysisRunStageCompletionService
import com.papertrail.api.evidence.service.EvidenceRetrievalService
import com.papertrail.api.infrastructure.messaging.events.PipelineEvent
import com.papertrail.api.evidence.repository.EvidenceRetrievalRepository
import com.papertrail.api.evidence.verification.service.EvidenceVerificationService
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@Component
class CitedPaperIndexingRequestedHandler(
    private val jdbc: JdbcTemplate,
    private val transactionTemplate: TransactionTemplate,
    private val objectMapper: ObjectMapper,
    private val evidenceRetrievalService: EvidenceRetrievalService,
    private val evidenceRetrievalRepository: EvidenceRetrievalRepository,
    private val evidenceVerificationService: EvidenceVerificationService,
    private val analysisRunStageCompletionService: AnalysisRunStageCompletionService,
) {
    fun isProcessed(eventId: UUID): Boolean = jdbc.queryForObject(
        "SELECT EXISTS (SELECT 1 FROM inbox_events WHERE event_id = ?)",
        Boolean::class.java,
        eventId,
    ) == true

    fun handle(serializedEvent: String): UUID {
        val event: PipelineEvent<CitedPaperIndexingRequestedPayload> = objectMapper.readValue(serializedEvent)
        require(event.eventType == CITED_PAPER_INDEXING_REQUESTED) { "Unsupported event type '${event.eventType}'." }
        if (isProcessed(event.eventId)) return event.eventId

        val run = jdbc.query(
            "SELECT document_id, source_content_sha256, status FROM analysis_runs WHERE id = ?",
            { rs, _ -> RunProvenance(
                documentId = rs.getObject("document_id", UUID::class.java),
                sourceHash = rs.getString("source_content_sha256"),
                status = rs.getString("status"),
            ) },
            event.analysisRunId,
        ).firstOrNull() ?: throw IllegalStateException("Queued Cited Paper indexing Analysis Run is missing.")
        if (run.documentId != event.payload.documentId || run.sourceHash != event.payload.sourceContentSha256) {
            throw IllegalStateException("Cited Paper indexing event provenance does not match its Analysis Run.")
        }
        if (run.status != "PROCESSING") throw IllegalStateException("Analysis Run is not accepting Cited Paper indexing work.")

        evidenceRetrievalService.retrieve(event.analysisRunId, event.payload.bibliographyEntryId)
        evidenceVerificationService.verifyReference(event.analysisRunId, event.payload.bibliographyEntryId)
        recordProcessed(event)
        return event.eventId
    }

    fun markFailed(event: PipelineEvent<CitedPaperIndexingRequestedPayload>, reason: String) {
        require(event.eventType == CITED_PAPER_INDEXING_REQUESTED) { "Unsupported event type '${event.eventType}'." }
        recordProcessed(event, reason)
    }

    private fun recordProcessed(event: PipelineEvent<CitedPaperIndexingRequestedPayload>, failureReason: String? = null) {
        transactionTemplate.executeWithoutResult {
            val inserted = jdbc.update(
                "INSERT INTO inbox_events (event_id, handler_name, processed_at) VALUES (?, ?, ?) ON CONFLICT (event_id) DO NOTHING",
                event.eventId,
                CITED_PAPER_INDEXING_HANDLER,
                Timestamp.from(Instant.now()),
            )
            if (inserted == 0) return@executeWithoutResult
            if (failureReason != null) {
                val failureCode = when (evidenceRetrievalRepository.status(event.analysisRunId, event.payload.bibliographyEntryId)) {
                    "PENDING" -> {
                        evidenceRetrievalRepository.markFailed(
                            event.analysisRunId,
                            event.payload.bibliographyEntryId,
                            INDEXING_RETRIES_EXHAUSTED,
                        )
                        INDEXING_RETRIES_EXHAUSTED
                    }
                    "COMPLETED" -> EVIDENCE_VERIFICATION_RETRIES_EXHAUSTED
                    else -> EVIDENCE_PROCESSING_INCOMPLETE
                }
                evidenceVerificationService.failFullText(
                    event.analysisRunId,
                    event.payload.bibliographyEntryId,
                    failureCode,
                )
                jdbc.update(
                    "UPDATE analysis_runs SET failure_reason = COALESCE(failure_reason, ?), updated_at = now() WHERE id = ? AND status = 'PROCESSING'",
                    failureReason,
                    event.analysisRunId,
                )
            }
            analysisRunStageCompletionService.completeParsedStageIfReady(event.analysisRunId)
        }
    }

    private data class RunProvenance(val documentId: UUID, val sourceHash: String, val status: String)

    companion object {
        const val INDEXING_RETRIES_EXHAUSTED = "INDEXING_RETRIES_EXHAUSTED"
        const val EVIDENCE_VERIFICATION_RETRIES_EXHAUSTED = "EVIDENCE_VERIFICATION_RETRIES_EXHAUSTED"
        const val EVIDENCE_PROCESSING_INCOMPLETE = "EVIDENCE_PROCESSING_INCOMPLETE"
    }
}
