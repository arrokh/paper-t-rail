package com.papertrail.api.evidence.retrieval

import com.papertrail.api.analysis.configuration.RetrievalConfigurationSnapshot
import com.papertrail.api.evidence.domain.EmbeddingProfile
import com.papertrail.api.evidence.domain.RankedEvidenceChunk
import com.papertrail.api.evidence.embedding.toPostgresVectorLiteral
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class PostgresHybridEvidenceRetriever(
    private val jdbc: JdbcTemplate,
    private val rankFusion: ReciprocalRankFusion,
) {
    fun retrieve(
        analysisRunId: UUID,
        bibliographyEntryId: UUID,
        claimText: String,
        queryVector: FloatArray,
        embeddingProfile: EmbeddingProfile,
        configuration: RetrievalConfigurationSnapshot,
    ): List<RankedEvidenceChunk> {
        require(queryVector.size == embeddingProfile.dimension) { "Claim embedding dimension does not match its pinned profile." }
        val vectorText = queryVector.toPostgresVectorLiteral()
        val vectorRankedIds = jdbc.query(
            """
            SELECT embedding.paper_chunk_id
              FROM paper_chunk_embeddings embedding
              JOIN paper_chunks chunk
                ON chunk.id = embedding.paper_chunk_id
               AND chunk.analysis_run_id = embedding.analysis_run_id
               AND chunk.bibliography_entry_id = embedding.bibliography_entry_id
             WHERE embedding.analysis_run_id = ?
               AND embedding.bibliography_entry_id = ?
               AND embedding.profile_hash = ?
               AND embedding.dimension = ?
             ORDER BY embedding.embedding <=> ?::vector ASC, chunk.chunk_order ASC
             LIMIT ?
            """.trimIndent(),
            { rs, _ -> rs.getObject("paper_chunk_id", UUID::class.java) },
            analysisRunId,
            bibliographyEntryId,
            embeddingProfile.profileHash,
            embeddingProfile.dimension,
            vectorText,
            configuration.vectorCandidateLimit,
        )
        val lexicalRankedIds = jdbc.query(
            """
            WITH query AS (SELECT websearch_to_tsquery('english', ?) AS terms)
            SELECT chunk.id
              FROM paper_chunks chunk CROSS JOIN query
             WHERE chunk.analysis_run_id = ?
               AND chunk.bibliography_entry_id = ?
               AND chunk.text_search @@ query.terms
             ORDER BY ts_rank_cd(chunk.text_search, query.terms) DESC, chunk.chunk_order ASC
             LIMIT ?
            """.trimIndent(),
            { rs, _ -> rs.getObject("id", UUID::class.java) },
            claimText,
            analysisRunId,
            bibliographyEntryId,
            configuration.lexicalCandidateLimit,
        )
        return rankFusion.fuse(
            vectorRankedIds = vectorRankedIds,
            lexicalRankedIds = lexicalRankedIds,
            rankConstant = configuration.reciprocalRankFusionConstant,
            limit = configuration.finalCandidateLimit,
        )
    }

}
