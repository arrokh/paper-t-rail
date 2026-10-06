package com.papertrail.api.evidence.queue

import com.fasterxml.jackson.databind.ObjectMapper
import com.papertrail.api.analysis.configuration.AnalysisConfigurationSnapshot
import com.papertrail.api.evidence.domain.EmbeddingProfile
import com.papertrail.api.infrastructure.messaging.events.PipelineEvent
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

enum class EvidenceIndexingEnqueueResult { QUEUED, NOT_ELIGIBLE, FAILED }

@Component
class CitedPaperIndexingQueue(
    private val jdbc: JdbcTemplate,
    private val objectMapper: ObjectMapper,
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
        val eligible = jdbc.queryForObject(
            """
            SELECT EXISTS (
                SELECT 1
                  FROM cited_paper_access access
                  JOIN claim_paper_verifications verification
                    ON verification.analysis_run_id = access.analysis_run_id
                   AND verification.bibliography_entry_id = access.bibliography_entry_id
                 WHERE access.analysis_run_id = ? AND access.bibliography_entry_id = ?
                   AND access.access_status = 'FULL_TEXT_AVAILABLE'
                   AND access.language = 'en'
                   AND access.language_detector_version IS NOT NULL
                   AND access.content_media_type IS NOT NULL
                   AND verification.verification_scope = 'FULL_TEXT'
                   AND verification.processing_status = 'PENDING'
            )
            """.trimIndent(),
            Boolean::class.java,
            analysisRunId,
            bibliographyEntryId,
        ) == true
        if (!eligible) return EvidenceIndexingEnqueueResult.NOT_ELIGIBLE

        val runConfiguration = jdbc.queryForObject(
            "SELECT configuration_snapshot::text FROM analysis_runs WHERE id = ?",
            String::class.java,
            analysisRunId,
        ) ?: throw IllegalStateException("Analysis Run configuration is missing for evidence retrieval.")
        val configuration = objectMapper.readValue(runConfiguration, AnalysisConfigurationSnapshot::class.java)
        val profile = EmbeddingProfile.forDiagnosis(configuration.embedding, configuration.retrieval.embeddingProfileHash)
        val retrieval = configuration.retrieval
        val indexingStatus = if (profile.dimension > 0) "PENDING" else "FAILED"
        val failureReason = if (profile.dimension > 0) null else "EMBEDDING_PROFILE_UNAVAILABLE"
        val inserted = jdbc.update(
            """
            INSERT INTO cited_paper_indexing (
                analysis_run_id, bibliography_entry_id, status, failure_reason,
                retrieval_profile_id, vector_candidate_limit, lexical_candidate_limit, final_candidate_limit,
                reciprocal_rank_fusion_constant, embedding_provider, embedding_model, embedding_version,
                embedding_dimension, embedding_profile_hash
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (analysis_run_id, bibliography_entry_id) DO NOTHING
            """.trimIndent(),
            analysisRunId,
            bibliographyEntryId,
            indexingStatus,
            failureReason,
            retrieval.profileId,
            retrieval.vectorCandidateLimit,
            retrieval.lexicalCandidateLimit,
            retrieval.finalCandidateLimit,
            retrieval.reciprocalRankFusionConstant,
            profile.providerId,
            profile.modelId,
            profile.version,
            profile.dimension,
            profile.profileHash,
        )
        if (inserted == 0) {
            val currentStatus = jdbc.queryForObject(
                "SELECT status FROM cited_paper_indexing WHERE analysis_run_id = ? AND bibliography_entry_id = ?",
                String::class.java,
                analysisRunId,
                bibliographyEntryId,
            )
            return if (currentStatus == "FAILED") EvidenceIndexingEnqueueResult.FAILED else EvidenceIndexingEnqueueResult.QUEUED
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
        jdbc.update(
            """
            INSERT INTO outbox_events (
                event_id, event_type, schema_version, analysis_run_id,
                correlation_id, causation_id, occurred_at, payload, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
            """.trimIndent(),
            event.eventId,
            event.eventType,
            event.schemaVersion,
            event.analysisRunId,
            event.correlationId,
            event.causationId,
            Timestamp.from(event.occurredAt),
            objectMapper.writeValueAsString(event),
            Timestamp.from(Instant.now()),
        )
        return EvidenceIndexingEnqueueResult.QUEUED
    }
}
