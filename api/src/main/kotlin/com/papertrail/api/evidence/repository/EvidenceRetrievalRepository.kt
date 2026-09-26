package com.papertrail.api.evidence.repository

import com.fasterxml.jackson.databind.ObjectMapper
import com.papertrail.api.analysis.configuration.AnalysisConfigurationSnapshot
import com.papertrail.api.evidence.domain.EmbeddedEvidenceChunk
import com.papertrail.api.evidence.domain.EmbeddingProfile
import com.papertrail.api.evidence.domain.EvidenceClaim
import com.papertrail.api.evidence.domain.EvidenceIndexingContext
import com.papertrail.api.evidence.domain.RankedEvidenceChunk
import com.papertrail.api.evidence.embedding.toPostgresVectorLiteral
import com.papertrail.api.evidence.retrieval.PostgresHybridEvidenceRetriever
import com.papertrail.api.citation.parsing.ParsedScientificDocument
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.support.TransactionTemplate
import java.sql.ResultSet
import java.util.UUID

@Repository
class EvidenceRetrievalRepository(
    private val jdbc: JdbcTemplate,
    private val objectMapper: ObjectMapper,
    private val transactionTemplate: TransactionTemplate,
    private val retriever: PostgresHybridEvidenceRetriever,
) {
    fun status(analysisRunId: UUID, bibliographyEntryId: UUID): String? = jdbc.query(
        "SELECT status FROM cited_paper_indexing WHERE analysis_run_id = ? AND bibliography_entry_id = ?",
        { rs, _ -> rs.getString("status") },
        analysisRunId,
        bibliographyEntryId,
    ).firstOrNull()

    fun isCompleted(analysisRunId: UUID, bibliographyEntryId: UUID): Boolean =
        status(analysisRunId, bibliographyEntryId) == "COMPLETED"

    fun loadContext(analysisRunId: UUID, bibliographyEntryId: UUID): EvidenceIndexingContext? {
        val access = jdbc.query(
            """
            SELECT access.canonical_paper_id, access.object_key, access.content_sha256,
                   access.content_media_type, access.language, access.language_detector_version,
                   run.configuration_snapshot::text AS configuration
              FROM cited_paper_access access
              JOIN cited_paper_indexing indexing
                ON indexing.analysis_run_id = access.analysis_run_id
               AND indexing.bibliography_entry_id = access.bibliography_entry_id
              JOIN analysis_runs run ON run.id = access.analysis_run_id
             WHERE access.analysis_run_id = ? AND access.bibliography_entry_id = ?
               AND indexing.status = 'PENDING'
               AND access.access_status = 'FULL_TEXT_AVAILABLE'
               AND access.language = 'en'
               AND access.language_detector_version IS NOT NULL
            """.trimIndent(),
            { rs, _ -> rs.toIndexingContext(analysisRunId, bibliographyEntryId) },
            analysisRunId,
            bibliographyEntryId,
        ).firstOrNull() ?: return null
        val claims = jdbc.query(
            """
            SELECT verification.id AS verification_id, claim.id AS atomic_claim_id, claim.claim_text
              FROM claim_paper_verifications verification
              JOIN atomic_claims claim
                ON claim.analysis_run_id = verification.analysis_run_id
               AND claim.id = verification.atomic_claim_id
             WHERE verification.analysis_run_id = ?
               AND verification.bibliography_entry_id = ?
               AND verification.verification_scope = 'FULL_TEXT'
               AND verification.processing_status = 'PENDING'
             ORDER BY claim.source_start_offset, claim.id
            """.trimIndent(),
            { rs, _ ->
                EvidenceClaim(
                    verificationId = rs.getObject("verification_id", UUID::class.java),
                    atomicClaimId = rs.getObject("atomic_claim_id", UUID::class.java),
                    text = rs.getString("claim_text"),
                )
            },
            analysisRunId,
            bibliographyEntryId,
        )
        if (claims.isEmpty()) return null
        return access.copy(claims = claims)
    }

    fun persistAndRetrieve(
        context: EvidenceIndexingContext,
        parsed: ParsedScientificDocument,
        chunks: List<EmbeddedEvidenceChunk>,
        claimVectors: Map<UUID, FloatArray>,
        profile: EmbeddingProfile,
    ): Int = transactionTemplate.execute {
        val state = jdbc.query(
            "SELECT status, cited_paper_parse_id, candidate_count FROM cited_paper_indexing WHERE analysis_run_id = ? AND bibliography_entry_id = ? FOR UPDATE",
            { rs, _ -> IndexingState(rs.getString("status"), rs.getObject("cited_paper_parse_id", UUID::class.java), rs.getInt("candidate_count")) },
            context.analysisRunId,
            context.bibliographyEntryId,
        ).firstOrNull() ?: throw IllegalStateException("Cited Paper indexing state is missing.")
        if (state.status == "COMPLETED") return@execute state.candidateCount
        require(state.status == "PENDING") { "Cited Paper indexing is not pending." }

        val parseId = persistParse(context, parsed)
        val persistedChunks = persistChunks(context, parseId, chunks, profile)
        var candidateCount = 0
        context.claims.forEach { claim ->
            val queryVector = claimVectors[claim.verificationId]
                ?: throw IllegalStateException("An Atomic Claim has no vector for the pinned embedding profile.")
            val ranked = retriever.retrieve(
                analysisRunId = context.analysisRunId,
                bibliographyEntryId = context.bibliographyEntryId,
                claimText = claim.text,
                queryVector = queryVector,
                embeddingProfile = profile,
                configuration = context.configuration.retrieval,
            )
            persistCandidates(context, claim.verificationId, ranked, profile)
            candidateCount += ranked.size
        }
        val updated = jdbc.update(
            """
            UPDATE cited_paper_indexing
               SET status = 'COMPLETED', cited_paper_parse_id = ?, candidate_count = ?, updated_at = now()
             WHERE analysis_run_id = ? AND bibliography_entry_id = ? AND status = 'PENDING'
            """.trimIndent(),
            parseId,
            candidateCount,
            context.analysisRunId,
            context.bibliographyEntryId,
        )
        if (updated != 1) throw IllegalStateException("Cited Paper indexing could not be completed.")
        require(persistedChunks.isNotEmpty()) { "The eligible Cited Paper did not produce any Evidence Passage chunks." }
        candidateCount
    } ?: throw IllegalStateException("Cited Paper indexing transaction returned no result.")

    fun markFailed(analysisRunId: UUID, bibliographyEntryId: UUID, reason: String) {
        jdbc.update(
            """
            UPDATE cited_paper_indexing
               SET status = 'FAILED', failure_reason = ?, updated_at = now()
             WHERE analysis_run_id = ? AND bibliography_entry_id = ? AND status = 'PENDING'
            """.trimIndent(),
            reason,
            analysisRunId,
            bibliographyEntryId,
        )
    }

    private fun persistParse(context: EvidenceIndexingContext, parsed: ParsedScientificDocument): UUID {
        require(parsed.normalizedSourceText.isNotBlank()) { "The Cited Paper parser returned no normalized text." }
        val existing = jdbc.query(
            """
            SELECT id, canonical_paper_id, content_sha256, parser_id, parser_version, language, language_detector_version, normalized_text
              FROM cited_paper_parses WHERE analysis_run_id = ? AND bibliography_entry_id = ?
            """.trimIndent(),
            { rs, _ -> ExistingParse(
                id = rs.getObject("id", UUID::class.java),
                canonicalPaperId = rs.getObject("canonical_paper_id", UUID::class.java),
                contentSha256 = rs.getString("content_sha256"),
                parserId = rs.getString("parser_id"),
                parserVersion = rs.getString("parser_version"),
                language = rs.getString("language"),
                languageDetectorVersion = rs.getString("language_detector_version"),
                normalizedText = rs.getString("normalized_text"),
            ) },
            context.analysisRunId,
            context.bibliographyEntryId,
        ).firstOrNull()
        if (existing != null) {
            require(existing.canonicalPaperId == context.canonicalPaperId &&
                existing.contentSha256 == context.contentSha256 &&
                existing.parserId == parsed.parserId && existing.parserVersion == parsed.parserVersion &&
                existing.language == context.language && existing.languageDetectorVersion == context.languageDetectorVersion &&
                existing.normalizedText == parsed.normalizedSourceText
            ) { "Persisted Cited Paper parser provenance does not match the pinned asset." }
            return existing.id
        }
        val parseId = UUID.randomUUID()
        jdbc.update(
            """
            INSERT INTO cited_paper_parses (
                id, analysis_run_id, bibliography_entry_id, canonical_paper_id, content_sha256,
                parser_id, parser_version, language, language_detector_version, normalized_text
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            parseId,
            context.analysisRunId,
            context.bibliographyEntryId,
            context.canonicalPaperId,
            context.contentSha256,
            parsed.parserId,
            parsed.parserVersion,
            context.language,
            context.languageDetectorVersion,
            parsed.normalizedSourceText,
        )
        return parseId
    }

    private fun persistChunks(
        context: EvidenceIndexingContext,
        parseId: UUID,
        chunks: List<EmbeddedEvidenceChunk>,
        profile: EmbeddingProfile,
    ): List<PersistedChunk> {
        chunks.forEach { embedded ->
            require(embedded.vector.size == profile.dimension) { "Evidence chunk embedding dimension does not match its profile." }
            jdbc.update(
                """
                INSERT INTO paper_chunks (
                    id, cited_paper_parse_id, analysis_run_id, bibliography_entry_id, chunk_order,
                    section_order, section_heading, paragraph_start, paragraph_end, text
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (cited_paper_parse_id, chunk_order) DO NOTHING
                """.trimIndent(),
                UUID.randomUUID(),
                parseId,
                context.analysisRunId,
                context.bibliographyEntryId,
                embedded.chunk.chunkOrder,
                embedded.chunk.sectionOrder,
                embedded.chunk.sectionHeading,
                embedded.chunk.paragraphStart,
                embedded.chunk.paragraphEnd,
                embedded.chunk.text,
            )
        }
        val persistedChunks = jdbc.query(
            "SELECT id, chunk_order, text FROM paper_chunks WHERE cited_paper_parse_id = ? ORDER BY chunk_order",
            { rs, _ -> PersistedChunk(rs.getObject("id", UUID::class.java), rs.getInt("chunk_order"), rs.getString("text")) },
            parseId,
        )
        require(persistedChunks.size == chunks.size && persistedChunks.zip(chunks).all { (stored, proposed) ->
            stored.chunkOrder == proposed.chunk.chunkOrder && stored.text == proposed.chunk.text
        }) { "Persisted Evidence Passage chunks do not match the pinned parse." }
        val vectorsByOrder = chunks.associateBy { it.chunk.chunkOrder }
        persistedChunks.forEach { chunk ->
            val vector = vectorsByOrder.getValue(chunk.chunkOrder).vector
            jdbc.update(
                """
                INSERT INTO paper_chunk_embeddings (
                    id, paper_chunk_id, analysis_run_id, bibliography_entry_id,
                    provider_id, model_id, provider_version, dimension, profile_hash, embedding
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::vector)
                ON CONFLICT (paper_chunk_id, profile_hash) DO NOTHING
                """.trimIndent(),
                UUID.randomUUID(),
                chunk.id,
                context.analysisRunId,
                context.bibliographyEntryId,
                profile.providerId,
                profile.modelId,
                profile.version,
                profile.dimension,
                profile.profileHash,
                vector.toPostgresVectorLiteral(),
            )
        }
        return persistedChunks
    }

    private fun persistCandidates(
        context: EvidenceIndexingContext,
        verificationId: UUID,
        candidates: List<RankedEvidenceChunk>,
        profile: EmbeddingProfile,
    ) {
        candidates.forEach { candidate ->
            jdbc.update(
                """
                INSERT INTO evidence_candidates (
                    id, verification_id, paper_chunk_id, analysis_run_id, bibliography_entry_id,
                    profile_hash, vector_rank, lexical_rank, fused_rank, fusion_score, retrieval_profile_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (verification_id, paper_chunk_id) DO NOTHING
                """.trimIndent(),
                UUID.randomUUID(),
                verificationId,
                candidate.chunkId,
                context.analysisRunId,
                context.bibliographyEntryId,
                profile.profileHash,
                candidate.vectorRank,
                candidate.lexicalRank,
                candidate.fusedRank,
                candidate.fusionScore,
                context.configuration.retrieval.profileId,
            )
        }
    }

    private fun ResultSet.toIndexingContext(analysisRunId: UUID, bibliographyEntryId: UUID): EvidenceIndexingContext {
        val configuration = objectMapper.readValue(getString("configuration"), AnalysisConfigurationSnapshot::class.java)
        return EvidenceIndexingContext(
            analysisRunId = analysisRunId,
            bibliographyEntryId = bibliographyEntryId,
            canonicalPaperId = getObject("canonical_paper_id", UUID::class.java),
            objectKey = getString("object_key") ?: throw IllegalStateException("Cited Paper full-text object key is missing."),
            contentSha256 = getString("content_sha256") ?: throw IllegalStateException("Cited Paper content hash is missing."),
            mediaType = getString("content_media_type") ?: throw IllegalStateException("Cited Paper media type is missing."),
            language = getString("language"),
            languageDetectorVersion = getString("language_detector_version"),
            configuration = configuration,
            claims = emptyList(),
        )
    }

    private data class IndexingState(val status: String, val parseId: UUID?, val candidateCount: Int)
    private data class ExistingParse(
        val id: UUID,
        val canonicalPaperId: UUID,
        val contentSha256: String,
        val parserId: String,
        val parserVersion: String,
        val language: String,
        val languageDetectorVersion: String,
        val normalizedText: String,
    )
    private data class PersistedChunk(val id: UUID, val chunkOrder: Int, val text: String)
}
