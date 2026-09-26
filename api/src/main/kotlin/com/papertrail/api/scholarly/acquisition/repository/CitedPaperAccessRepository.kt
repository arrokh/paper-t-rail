package com.papertrail.api.scholarly.acquisition.repository

import com.fasterxml.jackson.databind.ObjectMapper
import com.papertrail.api.scholarly.acquisition.domain.AcquiredFullText
import com.papertrail.api.scholarly.acquisition.domain.CitedPaperAccessDecision
import com.papertrail.api.scholarly.acquisition.domain.CitedPaperAccessReason
import com.papertrail.api.scholarly.acquisition.domain.OpenAccessDiscovery
import com.papertrail.api.scholarly.acquisition.report.CitedPaperAccessReport
import com.papertrail.api.scholarly.acquisition.report.CitedReferenceVerificationOutcome
import com.papertrail.api.scholarly.acquisition.report.EvidenceIndexingReport
import com.papertrail.api.scholarly.acquisition.report.EvidencePassageReport
import com.papertrail.api.infrastructure.crypto.sha256Hex
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.support.TransactionTemplate
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@Repository
class CitedPaperAccessRepository(
    private val jdbc: JdbcTemplate,
    private val objectMapper: ObjectMapper,
    private val transactionTemplate: TransactionTemplate,
) {
    fun accessExists(analysisRunId: UUID, bibliographyEntryId: UUID): Boolean = jdbc.queryForObject(
        "SELECT EXISTS (SELECT 1 FROM cited_paper_access WHERE analysis_run_id = ? AND bibliography_entry_id = ?)",
        Boolean::class.java,
        analysisRunId,
        bibliographyEntryId,
    ) == true

    fun resolvedReference(analysisRunId: UUID, bibliographyEntryId: UUID): ResolvedCitedReference? = jdbc.query(
        """
        SELECT b.id, b.parsed_title, b.parsed_authors::text AS parsed_authors, b.parsed_year, b.parsed_doi, b.reference_type,
               p.id AS canonical_paper_id, p.doi AS canonical_doi
          FROM bibliography_entry_resolutions r
          JOIN bibliography_entries b
            ON b.analysis_run_id = r.analysis_run_id AND b.id = r.bibliography_entry_id
          JOIN canonical_papers p ON p.id = r.canonical_paper_id
         WHERE r.analysis_run_id = ? AND r.bibliography_entry_id = ? AND r.status = 'RESOLVED'
        """.trimIndent(),
        { rs, _ ->
            ResolvedCitedReference(
                bibliographyEntryId = rs.getObject("id", UUID::class.java),
                title = rs.getString("parsed_title"),
                authors = objectMapper.readValue(
                    rs.getString("parsed_authors"),
                    objectMapper.typeFactory.constructCollectionType(List::class.java, String::class.java),
                ),
                year = rs.getObject("parsed_year", Integer::class.java)?.toInt(),
                doi = rs.getString("parsed_doi") ?: rs.getString("canonical_doi"),
                referenceType = rs.getString("reference_type"),
                canonicalPaperId = rs.getObject("canonical_paper_id", UUID::class.java),
            )
        },
        analysisRunId,
        bibliographyEntryId,
    ).firstOrNull()

    fun save(
        analysisRunId: UUID,
        reference: ResolvedCitedReference,
        discovery: OpenAccessDiscovery?,
        decision: CitedPaperAccessDecision,
        accessReason: CitedPaperAccessReason?,
        locationUrl: String?,
        license: String?,
        version: String?,
        hostType: String?,
        providerId: String,
        discoveredAt: Instant,
        objectKey: String?,
        fullText: AcquiredFullText?,
        language: String?,
        languageDetectorVersion: String?,
    ): Boolean = transactionTemplate.execute {
            val contentHash = fullText?.bytes?.let(::sha256Hex)
            val inserted = jdbc.update(
                """
                INSERT INTO cited_paper_access (
                    analysis_run_id, bibliography_entry_id, canonical_paper_id, access_status,
                    provider_id, access_reason, metadata_available, abstract_available, source_url, license_identifier,
                    location_version, location_host_type, discovered_at, object_key, content_sha256,
                    content_media_type, language, language_detector_version
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (analysis_run_id, bibliography_entry_id) DO NOTHING
                """.trimIndent(),
                analysisRunId,
                reference.bibliographyEntryId,
                reference.canonicalPaperId,
                decision.accessStatus.name,
                providerId,
                accessReason?.name,
                discovery?.metadataAvailable == true || discovery?.abstractAvailable == true || discovery?.locations?.isNotEmpty() == true || fullText != null,
                discovery?.abstractAvailable == true,
                locationUrl,
                license,
                version,
                hostType,
                Timestamp.from(discoveredAt),
                objectKey,
                contentHash,
                fullText?.mediaType?.substringBefore(';')?.trim()?.lowercase(),
                language,
                languageDetectorVersion,
            )
            if (inserted == 0) return@execute false
            val finalStatus = decision.finalVerificationStatus?.name
            jdbc.update(
                """
                INSERT INTO claim_paper_verifications (
                    id, analysis_run_id, atomic_claim_id, bibliography_entry_id, canonical_paper_id,
                    processing_status, verification_scope, terminal_reason, final_status
                )
                SELECT gen_random_uuid(), ?, claims.atomic_claim_id, ?, ?, ?, ?, ?, ?
                  FROM (
                      SELECT DISTINCT link.atomic_claim_id
                        FROM atomic_claim_citation_targets link
                        JOIN citation_targets target
                          ON target.analysis_run_id = link.analysis_run_id
                         AND target.id = link.citation_target_id
                       WHERE target.analysis_run_id = ? AND target.bibliography_entry_id = ?
                  ) claims
                ON CONFLICT (analysis_run_id, atomic_claim_id, bibliography_entry_id) DO NOTHING
                """.trimIndent(),
                analysisRunId,
                reference.bibliographyEntryId,
                reference.canonicalPaperId,
                if (finalStatus == null) "PENDING" else "COMPLETED",
                decision.verificationScope.name,
                decision.terminalReason,
                finalStatus,
                analysisRunId,
                reference.bibliographyEntryId,
            )
            true
        } ?: false

    fun isObjectKeyReferenced(objectKey: String): Boolean = jdbc.queryForObject(
        "SELECT EXISTS (SELECT 1 FROM cited_paper_access WHERE object_key = ?)",
        Boolean::class.java,
        objectKey,
    ) == true

    fun reportEntries(analysisRunId: UUID): Map<String, CitedPaperAccessReport> {
        val outcomesByReference = verificationOutcomes(analysisRunId)
        return jdbc.query(
            """
            SELECT b.id AS bibliography_entry_id, b.local_reference_key, a.access_status, a.access_reason,
                   a.provider_id, a.source_url, a.license_identifier, a.location_version, a.location_host_type,
                   a.discovered_at, a.content_sha256, a.language, a.language_detector_version,
                   CASE WHEN indexing.analysis_run_id IS NULL THEN NULL ELSE jsonb_build_object(
                       'status', indexing.status,
                       'failureReason', indexing.failure_reason,
                       'assetId', a.asset_id,
                       'parserProvider', asset.parser_id,
                       'parserVersion', asset.parser_version,
                       'contentSha256', asset.content_sha256,
                       'language', asset.language,
                       'languageDetectorVersion', asset.language_detector_version,
                       'retrievalProfile', jsonb_build_object(
                           'profileId', indexing.retrieval_profile_id,
                           'vectorCandidateLimit', indexing.vector_candidate_limit,
                           'lexicalCandidateLimit', indexing.lexical_candidate_limit,
                           'finalCandidateLimit', indexing.final_candidate_limit,
                           'reciprocalRankFusionConstant', indexing.reciprocal_rank_fusion_constant,
                           'embeddingProvider', indexing.embedding_provider,
                           'embeddingModel', indexing.embedding_model,
                           'embeddingVersion', indexing.embedding_version,
                           'embeddingDimension', indexing.embedding_dimension,
                           'embeddingProfileHash', indexing.embedding_profile_hash
                       )
                   ) END::text AS evidence_indexing
              FROM cited_paper_access a
              JOIN bibliography_entries b
                ON b.analysis_run_id = a.analysis_run_id AND b.id = a.bibliography_entry_id
              LEFT JOIN cited_paper_indexing indexing
                ON indexing.analysis_run_id = a.analysis_run_id
               AND indexing.bibliography_entry_id = a.bibliography_entry_id
              LEFT JOIN cited_paper_parses asset
                ON asset.id = indexing.cited_paper_parse_id
               AND asset.analysis_run_id = indexing.analysis_run_id
               AND asset.bibliography_entry_id = indexing.bibliography_entry_id
             WHERE a.analysis_run_id = ?
             ORDER BY b.entry_order
            """.trimIndent(),
            { rs, _ -> rs.toAccessReportEntry(outcomesByReference) },
            analysisRunId,
        ).associate { it.localReferenceKey to it.report }
    }

    private fun verificationOutcomes(analysisRunId: UUID): Map<UUID, List<CitedReferenceVerificationOutcome>> {
        val passageType = objectMapper.typeFactory.constructCollectionType(List::class.java, EvidencePassageReport::class.java)
        return jdbc.query(
            """
            SELECT verification.bibliography_entry_id, verification.atomic_claim_id, claim.claim_text,
                   verification.final_status, verification.verification_scope, verification.terminal_reason,
                   COALESCE(jsonb_agg(jsonb_build_object(
                       'id', candidate.id,
                       'text', chunk.text,
                       'sectionOrder', chunk.section_order,
                       'sectionHeading', chunk.section_heading,
                       'paragraphStart', chunk.paragraph_start,
                       'paragraphEnd', chunk.paragraph_end,
                       'pageNumber', chunk.page_number,
                       'vectorRank', candidate.vector_rank,
                       'lexicalRank', candidate.lexical_rank,
                       'fusedRank', candidate.fused_rank,
                       'fusionScore', candidate.fusion_score,
                       'sourceAssetId', source_asset.asset_id,
                       'contentSha256', asset.content_sha256,
                       'parserProvider', asset.parser_id,
                       'parserVersion', asset.parser_version,
                       'language', asset.language,
                       'languageDetectorVersion', asset.language_detector_version,
                       'retrievalProfile', jsonb_build_object(
                           'profileId', candidate.retrieval_profile_id,
                           'vectorCandidateLimit', indexing.vector_candidate_limit,
                           'lexicalCandidateLimit', indexing.lexical_candidate_limit,
                           'finalCandidateLimit', indexing.final_candidate_limit,
                           'reciprocalRankFusionConstant', indexing.reciprocal_rank_fusion_constant,
                           'embeddingProvider', indexing.embedding_provider,
                           'embeddingModel', indexing.embedding_model,
                           'embeddingVersion', indexing.embedding_version,
                           'embeddingDimension', indexing.embedding_dimension,
                           'embeddingProfileHash', candidate.profile_hash
                       )
                   ) ORDER BY candidate.fused_rank) FILTER (WHERE candidate.id IS NOT NULL), '[]'::jsonb)::text AS evidence_passages
              FROM claim_paper_verifications verification
              JOIN atomic_claims claim
                ON claim.analysis_run_id = verification.analysis_run_id
               AND claim.id = verification.atomic_claim_id
              LEFT JOIN cited_paper_indexing indexing
                ON indexing.analysis_run_id = verification.analysis_run_id
               AND indexing.bibliography_entry_id = verification.bibliography_entry_id
              LEFT JOIN evidence_candidates candidate
                ON candidate.verification_id = verification.id
               AND candidate.analysis_run_id = verification.analysis_run_id
               AND candidate.bibliography_entry_id = verification.bibliography_entry_id
              LEFT JOIN paper_chunks chunk
                ON chunk.id = candidate.paper_chunk_id
               AND chunk.analysis_run_id = candidate.analysis_run_id
               AND chunk.bibliography_entry_id = candidate.bibliography_entry_id
              LEFT JOIN cited_paper_parses asset
                ON asset.id = chunk.cited_paper_parse_id
               AND asset.analysis_run_id = chunk.analysis_run_id
               AND asset.bibliography_entry_id = chunk.bibliography_entry_id
              LEFT JOIN cited_paper_access source_asset
                ON source_asset.analysis_run_id = asset.analysis_run_id
               AND source_asset.bibliography_entry_id = asset.bibliography_entry_id
               AND source_asset.canonical_paper_id = asset.canonical_paper_id
               AND source_asset.content_sha256 = asset.content_sha256
             WHERE verification.analysis_run_id = ?
             GROUP BY verification.id, verification.bibliography_entry_id, verification.atomic_claim_id,
                      claim.claim_text, claim.source_start_offset, verification.final_status,
                      verification.verification_scope, verification.terminal_reason,
                      indexing.vector_candidate_limit, indexing.lexical_candidate_limit, indexing.final_candidate_limit,
                      indexing.reciprocal_rank_fusion_constant, indexing.embedding_provider, indexing.embedding_model,
                      indexing.embedding_version, indexing.embedding_dimension
             ORDER BY claim.source_start_offset, verification.atomic_claim_id
            """.trimIndent(),
            { rs, _ ->
                val passages = objectMapper.readValue<List<EvidencePassageReport>>(
                    rs.getString("evidence_passages"),
                    passageType,
                )
                VerificationOutcomeRow(
                    bibliographyEntryId = rs.getObject("bibliography_entry_id", UUID::class.java),
                    outcome = CitedReferenceVerificationOutcome(
                        atomicClaimId = rs.getObject("atomic_claim_id", UUID::class.java),
                        claimText = rs.getString("claim_text"),
                        finalStatus = rs.getString("final_status"),
                        verificationScope = rs.getString("verification_scope"),
                        terminalReason = rs.getString("terminal_reason"),
                        evidencePassages = passages,
                    ),
                )
            },
            analysisRunId,
        ).groupBy(VerificationOutcomeRow::bibliographyEntryId)
            .mapValues { (_, rows) -> rows.map(VerificationOutcomeRow::outcome) }
    }

    private fun ResultSet.toAccessReportEntry(
        outcomesByReference: Map<UUID, List<CitedReferenceVerificationOutcome>>,
    ): AccessReportEntry {
        val bibliographyEntryId = getObject("bibliography_entry_id", UUID::class.java)
        val indexingJson = getString("evidence_indexing")
        val indexing = indexingJson?.let { objectMapper.readValue(it, EvidenceIndexingReport::class.java) }
        return AccessReportEntry(
            localReferenceKey = getString("local_reference_key"),
            report = CitedPaperAccessReport(
                accessStatus = getString("access_status"),
                accessReason = getString("access_reason"),
                providerId = getString("provider_id"),
                sourceUrl = getString("source_url"),
                license = getString("license_identifier"),
                version = getString("location_version"),
                hostType = getString("location_host_type"),
                discoveredAt = getTimestamp("discovered_at").toInstant(),
                contentSha256 = getString("content_sha256"),
                language = getString("language"),
                languageDetectorVersion = getString("language_detector_version"),
                verificationOutcomes = outcomesByReference[bibliographyEntryId].orEmpty(),
                evidenceIndexing = indexing,
            ),
        )
    }

    private data class VerificationOutcomeRow(
        val bibliographyEntryId: UUID,
        val outcome: CitedReferenceVerificationOutcome,
    )

    private data class AccessReportEntry(
        val localReferenceKey: String,
        val report: CitedPaperAccessReport,
    )
}
