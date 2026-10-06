package com.papertrail.api.evidence.repository

import com.papertrail.api.utils.JsonUtil
import com.papertrail.api.analysis.configuration.AnalysisConfigurationSnapshot
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class CitedPaperIndexingRepository(
    private val jdbc: JdbcTemplate,
) {
    fun isEligible(analysisRunId: UUID, bibliographyEntryId: UUID): Boolean = jdbc.queryForObject(
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

    fun configuration(analysisRunId: UUID): AnalysisConfigurationSnapshot? = jdbc.query(
        "SELECT configuration_snapshot::text FROM analysis_runs WHERE id = ?",
        { rs, _ -> JsonUtil.fromJson(rs.getString(1), AnalysisConfigurationSnapshot::class.java) },
        analysisRunId,
    ).firstOrNull()

    fun insertIndexing(
        analysisRunId: UUID,
        bibliographyEntryId: UUID,
        status: String,
        failureReason: String?,
        retrievalProfileId: String,
        vectorCandidateLimit: Int,
        lexicalCandidateLimit: Int,
        finalCandidateLimit: Int,
        reciprocalRankFusionConstant: Int,
        embeddingProvider: String,
        embeddingModel: String,
        embeddingVersion: String,
        embeddingDimension: Int,
        embeddingProfileHash: String,
    ): Boolean = jdbc.update(
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
        status,
        failureReason,
        retrievalProfileId,
        vectorCandidateLimit,
        lexicalCandidateLimit,
        finalCandidateLimit,
        reciprocalRankFusionConstant,
        embeddingProvider,
        embeddingModel,
        embeddingVersion,
        embeddingDimension,
        embeddingProfileHash,
    ) == 1

    fun status(analysisRunId: UUID, bibliographyEntryId: UUID): String? = jdbc.query(
        "SELECT status FROM cited_paper_indexing WHERE analysis_run_id = ? AND bibliography_entry_id = ?",
        { rs, _ -> rs.getString("status") },
        analysisRunId,
        bibliographyEntryId,
    ).firstOrNull()
}
