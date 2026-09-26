package com.papertrail.api.evidence.repository

import com.papertrail.api.evidence.report.EvidenceIndexingReport
import com.papertrail.api.evidence.report.EvidencePassageReport
import com.papertrail.api.evidence.report.EvidenceRetrievalProfileReport
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.util.UUID

@Repository
class EvidenceReportRepository(
    private val jdbc: JdbcTemplate,
) {
    fun indexingReportsByReference(analysisRunId: UUID): Map<UUID, EvidenceIndexingReport> = jdbc.query(
        """
        SELECT indexing.bibliography_entry_id,
               indexing.status,
               indexing.failure_reason,
               access.asset_id,
               asset.parser_id,
               asset.parser_version,
               asset.content_sha256,
               asset.language,
               asset.language_detector_version,
               indexing.retrieval_profile_id AS profile_id,
               indexing.vector_candidate_limit,
               indexing.lexical_candidate_limit,
               indexing.final_candidate_limit,
               indexing.reciprocal_rank_fusion_constant,
               indexing.embedding_provider,
               indexing.embedding_model,
               indexing.embedding_version,
               indexing.embedding_dimension,
               indexing.embedding_profile_hash
          FROM cited_paper_access access
          JOIN cited_paper_indexing indexing
            ON indexing.analysis_run_id = access.analysis_run_id
           AND indexing.bibliography_entry_id = access.bibliography_entry_id
          LEFT JOIN cited_paper_parses asset
            ON asset.id = indexing.cited_paper_parse_id
           AND asset.analysis_run_id = indexing.analysis_run_id
           AND asset.bibliography_entry_id = indexing.bibliography_entry_id
         WHERE access.analysis_run_id = ?
         ORDER BY access.bibliography_entry_id
        """.trimIndent(),
        { rs, _ ->
            rs.getObject("bibliography_entry_id", UUID::class.java) to EvidenceIndexingReport(
                status = rs.getString("status"),
                failureReason = rs.getString("failure_reason"),
                assetId = rs.getObject("asset_id", UUID::class.java),
                parserProvider = rs.getString("parser_id"),
                parserVersion = rs.getString("parser_version"),
                contentSha256 = rs.getString("content_sha256"),
                language = rs.getString("language"),
                languageDetectorVersion = rs.getString("language_detector_version"),
                retrievalProfile = rs.toRetrievalProfileReport(),
            )
        },
        analysisRunId,
    ).toMap()

    fun passagesByVerification(analysisRunId: UUID): Map<UUID, List<EvidencePassageReport>> = jdbc.query(
        """
        SELECT candidate.verification_id,
               candidate.id,
               chunk.text,
               chunk.section_order,
               chunk.section_heading,
               chunk.paragraph_start,
               chunk.paragraph_end,
               chunk.page_number,
               candidate.vector_rank,
               candidate.lexical_rank,
               candidate.fused_rank,
               candidate.fusion_score,
               source_asset.asset_id AS source_asset_id,
               asset.content_sha256,
               asset.parser_id,
               asset.parser_version,
               asset.language,
               asset.language_detector_version,
               candidate.retrieval_profile_id AS profile_id,
               indexing.vector_candidate_limit,
               indexing.lexical_candidate_limit,
               indexing.final_candidate_limit,
               indexing.reciprocal_rank_fusion_constant,
               indexing.embedding_provider,
               indexing.embedding_model,
               indexing.embedding_version,
               indexing.embedding_dimension,
               candidate.profile_hash AS embedding_profile_hash
          FROM evidence_candidates candidate
          JOIN paper_chunks chunk
            ON chunk.id = candidate.paper_chunk_id
           AND chunk.analysis_run_id = candidate.analysis_run_id
           AND chunk.bibliography_entry_id = candidate.bibliography_entry_id
          JOIN cited_paper_parses asset
            ON asset.id = chunk.cited_paper_parse_id
           AND asset.analysis_run_id = chunk.analysis_run_id
           AND asset.bibliography_entry_id = chunk.bibliography_entry_id
          JOIN cited_paper_access source_asset
            ON source_asset.analysis_run_id = asset.analysis_run_id
           AND source_asset.bibliography_entry_id = asset.bibliography_entry_id
           AND source_asset.canonical_paper_id = asset.canonical_paper_id
           AND source_asset.content_sha256 = asset.content_sha256
          JOIN cited_paper_indexing indexing
            ON indexing.analysis_run_id = candidate.analysis_run_id
           AND indexing.bibliography_entry_id = candidate.bibliography_entry_id
         WHERE candidate.analysis_run_id = ?
         ORDER BY candidate.verification_id, candidate.fused_rank
        """.trimIndent(),
        { rs, _ ->
            rs.getObject("verification_id", UUID::class.java) to EvidencePassageReport(
                id = rs.getObject("id", UUID::class.java),
                text = rs.getString("text"),
                sectionOrder = rs.getInt("section_order"),
                sectionHeading = rs.getString("section_heading"),
                paragraphStart = rs.getInt("paragraph_start"),
                paragraphEnd = rs.getInt("paragraph_end"),
                pageNumber = rs.getObject("page_number", Integer::class.java)?.toInt(),
                vectorRank = rs.getObject("vector_rank", Integer::class.java)?.toInt(),
                lexicalRank = rs.getObject("lexical_rank", Integer::class.java)?.toInt(),
                fusedRank = rs.getInt("fused_rank"),
                fusionScore = rs.getDouble("fusion_score"),
                sourceAssetId = rs.getObject("source_asset_id", UUID::class.java),
                contentSha256 = rs.getString("content_sha256"),
                parserProvider = rs.getString("parser_id"),
                parserVersion = rs.getString("parser_version"),
                language = rs.getString("language"),
                languageDetectorVersion = rs.getString("language_detector_version"),
                retrievalProfile = rs.toRetrievalProfileReport(),
            )
        },
        analysisRunId,
    ).groupBy({ it.first }, { it.second })

    private fun ResultSet.toRetrievalProfileReport() = EvidenceRetrievalProfileReport(
        profileId = getString("profile_id"),
        vectorCandidateLimit = getInt("vector_candidate_limit"),
        lexicalCandidateLimit = getInt("lexical_candidate_limit"),
        finalCandidateLimit = getInt("final_candidate_limit"),
        reciprocalRankFusionConstant = getInt("reciprocal_rank_fusion_constant"),
        embeddingProvider = getString("embedding_provider"),
        embeddingModel = getString("embedding_model"),
        embeddingVersion = getString("embedding_version"),
        embeddingDimension = getInt("embedding_dimension"),
        embeddingProfileHash = getString("embedding_profile_hash"),
    )
}
