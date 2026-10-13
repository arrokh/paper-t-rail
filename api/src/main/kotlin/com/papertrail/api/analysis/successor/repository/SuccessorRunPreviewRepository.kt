package com.papertrail.api.analysis.successor.repository

import com.papertrail.api.analysis.configuration.AnalysisConfigurationSnapshot
import com.papertrail.api.utils.JsonUtil
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class SuccessorRunPreviewRepository(
    private val jdbc: JdbcTemplate,
) {
    fun predecessor(analysisRunId: UUID): SuccessorPredecessorSnapshot? = jdbc.query(
        """
        SELECT run.status,
               run.source_content_sha256,
               run.configuration_snapshot::text AS configuration_snapshot,
               EXISTS (
                   SELECT 1
                     FROM parsed_document_parses parsed
                    WHERE parsed.analysis_run_id = run.id
                      AND parsed.source_content_sha256 = run.source_content_sha256
               ) AS has_source_parse,
               (SELECT count(*) FROM bibliography_entries entry WHERE entry.analysis_run_id = run.id) AS bibliography_entry_count,
               (SELECT count(*) FROM atomic_claims claim WHERE claim.analysis_run_id = run.id) AS claim_count
          FROM analysis_runs run
         WHERE run.id = ?
        """.trimIndent(),
        { rs, _ ->
            SuccessorPredecessorSnapshot(
                status = rs.getString("status"),
                sourceContentSha256 = rs.getString("source_content_sha256"),
                configuration = JsonUtil.fromJson(
                    rs.getString("configuration_snapshot"),
                    AnalysisConfigurationSnapshot::class.java,
                ),
                hasSourceParse = rs.getBoolean("has_source_parse"),
                bibliographyEntryCount = rs.getLong("bibliography_entry_count"),
                claimCount = rs.getLong("claim_count"),
            )
        },
        analysisRunId,
    ).firstOrNull()

    fun selectedEntry(analysisRunId: UUID, bibliographyEntryId: UUID): SuccessorEntrySnapshot? = jdbc.query(
        """
        SELECT entry.local_reference_key,
               entry.entry_order,
               entry.reference_type,
               resolution.status AS resolution_status,
               resolution.canonical_paper_id,
               (
                   SELECT count(DISTINCT claim.id)
                     FROM atomic_claim_citation_targets claim_target
                     JOIN atomic_claims claim
                       ON claim.analysis_run_id = claim_target.analysis_run_id
                      AND claim.id = claim_target.atomic_claim_id
                     JOIN citation_targets target
                       ON target.analysis_run_id = claim_target.analysis_run_id
                      AND target.id = claim_target.citation_target_id
                    WHERE target.analysis_run_id = entry.analysis_run_id
                      AND target.bibliography_entry_id = entry.id
               ) AS claim_count
          FROM bibliography_entries entry
          LEFT JOIN bibliography_entry_resolutions resolution
            ON resolution.analysis_run_id = entry.analysis_run_id
           AND resolution.bibliography_entry_id = entry.id
         WHERE entry.analysis_run_id = ?
           AND entry.id = ?
        """.trimIndent(),
        { rs, _ ->
            SuccessorEntrySnapshot(
                bibliographyEntryId = bibliographyEntryId,
                localReferenceKey = rs.getString("local_reference_key"),
                entryOrder = rs.getInt("entry_order"),
                referenceType = rs.getString("reference_type"),
                resolutionStatus = rs.getString("resolution_status"),
                canonicalPaperId = rs.getObject("canonical_paper_id", UUID::class.java),
                claimCount = rs.getLong("claim_count"),
            )
        },
        analysisRunId,
        bibliographyEntryId,
    ).firstOrNull()

    fun referenceKeys(analysisRunId: UUID): List<SuccessorReferenceKey> = jdbc.query(
        """
        SELECT id, local_reference_key
          FROM bibliography_entries
         WHERE analysis_run_id = ?
         ORDER BY entry_order, id
        """.trimIndent(),
        { rs, _ ->
            SuccessorReferenceKey(
                bibliographyEntryId = rs.getObject("id", UUID::class.java),
                localReferenceKey = rs.getString("local_reference_key"),
            )
        },
        analysisRunId,
    )

    fun accessProviders(analysisRunId: UUID): List<SuccessorAccessProviderSnapshot> = jdbc.query(
        """
        SELECT bibliography_entry_id, provider_id
          FROM cited_paper_access
         WHERE analysis_run_id = ?
           AND provider_id IS NOT NULL
         ORDER BY bibliography_entry_id, provider_id
        """.trimIndent(),
        { rs, _ ->
            SuccessorAccessProviderSnapshot(
                bibliographyEntryId = rs.getObject("bibliography_entry_id", UUID::class.java),
                providerId = rs.getString("provider_id"),
            )
        },
        analysisRunId,
    )
}

data class SuccessorPredecessorSnapshot(
    val status: String,
    val sourceContentSha256: String,
    val configuration: AnalysisConfigurationSnapshot,
    val hasSourceParse: Boolean,
    val bibliographyEntryCount: Long,
    val claimCount: Long,
)

data class SuccessorEntrySnapshot(
    val bibliographyEntryId: UUID,
    val localReferenceKey: String,
    val entryOrder: Int,
    val referenceType: String,
    val resolutionStatus: String?,
    val canonicalPaperId: UUID?,
    val claimCount: Long,
)

data class SuccessorReferenceKey(
    val bibliographyEntryId: UUID,
    val localReferenceKey: String,
)

data class SuccessorAccessProviderSnapshot(
    val bibliographyEntryId: UUID,
    val providerId: String,
)
