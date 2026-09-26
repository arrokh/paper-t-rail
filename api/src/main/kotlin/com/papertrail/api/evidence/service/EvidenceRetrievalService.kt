package com.papertrail.api.evidence.service

import com.papertrail.api.document.storage.SourceDocumentObjectStore
import com.papertrail.api.evidence.chunking.SectionAwareEvidenceChunker
import com.papertrail.api.evidence.domain.EmbeddedEvidenceChunk
import com.papertrail.api.evidence.domain.EmbeddingProfile
import com.papertrail.api.evidence.embedding.EmbeddingProvider
import com.papertrail.api.evidence.embedding.EmbeddingRequestContext
import com.papertrail.api.evidence.parsing.CitedPaperParser
import com.papertrail.api.infrastructure.providers.DataCategory
import com.papertrail.api.evidence.repository.EvidenceRetrievalRepository
import com.papertrail.api.infrastructure.crypto.sha256Hex
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class EvidenceRetrievalService(
    private val objectStore: SourceDocumentObjectStore,
    private val citedPaperParser: CitedPaperParser,
    private val chunker: SectionAwareEvidenceChunker,
    private val embeddingProviders: List<EmbeddingProvider>,
    private val repository: EvidenceRetrievalRepository,
) {
    fun retrieve(analysisRunId: UUID, bibliographyEntryId: UUID) {
        if (repository.isCompleted(analysisRunId, bibliographyEntryId)) return
        val context = repository.loadContext(analysisRunId, bibliographyEntryId)
            ?: throw IllegalStateException("Eligible Cited Paper evidence indexing context is unavailable.")
        val profile = EmbeddingProfile.from(context.configuration.embedding)
        require(context.configuration.retrieval.embeddingProfileHash == profile.profileHash) {
            "The Analysis Run retrieval profile does not match its pinned embedding configuration."
        }
        require(context.language == "en" && context.languageDetectorVersion.isNotBlank()) {
            "Only confidently English Cited Paper assets with recorded detector provenance can be indexed."
        }

        val content = objectStore.get(context.objectKey)
        require(sha256Hex(content) == context.contentSha256) { "Stored Cited Paper failed its SHA-256 integrity check." }
        val parsed = citedPaperParser.parse(content, context.mediaType)
        if (context.mediaType == "application/pdf") {
            require(parsed.parserId == context.configuration.sourceParser.provider &&
                parsed.parserVersion == context.configuration.sourceParser.version
            ) { "Cited Paper parser identity does not match the Analysis Run's pinned parser." }
        }
        val chunks = chunker.chunk(parsed.sections)
        require(chunks.isNotEmpty()) { "The Cited Paper parser returned no usable section paragraphs." }
        val provider = embeddingProviders.singleOrNull {
            it.providerId == profile.providerId && it.modelId == profile.modelId && it.version == profile.version && it.dimension == profile.dimension
        } ?: throw IllegalStateException("The pinned embedding profile is unavailable.")
        fun embed(text: String, category: DataCategory): FloatArray {
            val vector = provider.embed(text, EmbeddingRequestContext(context.configuration, category))
            require(vector.size == profile.dimension) {
                "Embedding provider returned a vector dimension that does not match the Analysis Run profile."
            }
            require(vector.all(Float::isFinite)) { "Embedding provider returned a non-finite vector value." }
            return vector
        }
        val embeddedChunks = chunks.map { chunk -> EmbeddedEvidenceChunk(chunk, embed(chunk.text, DataCategory.CITED_PAPER_CHUNKS)) }
        val claimVectors = context.claims.associate { claim ->
            claim.verificationId to embed(claim.text, DataCategory.ATOMIC_CLAIMS)
        }
        repository.persistAndRetrieve(context, parsed, embeddedChunks, claimVectors, profile)
    }
}
