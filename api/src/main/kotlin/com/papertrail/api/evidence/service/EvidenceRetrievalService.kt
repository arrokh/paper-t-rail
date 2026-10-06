package com.papertrail.api.evidence.service

import com.papertrail.api.infrastructure.storage.SourceDocumentObjectStore
import com.papertrail.api.analysis.execution.service.AnalysisRunExecutionService
import com.papertrail.api.analysis.execution.ExecutionSpanSpec
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
    private val executionService: AnalysisRunExecutionService? = null,
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

        repository.requireActiveRun(analysisRunId)
        val content = objectStore.get(context.objectKey)
        require(sha256Hex(content) == context.contentSha256) { "Stored Cited Paper failed its SHA-256 integrity check." }
        repository.requireActiveRun(analysisRunId)
        val selectedParser = context.configuration.citedPaperParserSelection()
        val parse = { citedPaperParser.parse(content, context.mediaType, selectedParser) }
        val parsed = executionService?.record(
            analysisRunId,
            ExecutionSpanSpec(
                "evidence", "PROVIDER", "Parse cited-paper source",
                providerId = selectedParser.provider,
                modelId = selectedParser.version,
            ),
            parse,
        ) ?: parse()
        if (context.mediaType == "application/pdf") {
            require(parsed.parserId == selectedParser.provider && parsed.parserVersion == selectedParser.version) {
                "Cited Paper parser identity does not match the Analysis Run's pinned parser."
            }
        }
        val chunk = { chunker.chunk(parsed.sections) }
        val chunks = executionService?.record(
            analysisRunId,
            ExecutionSpanSpec("evidence", "TRANSFORMATION", "Chunk cited-paper sections"),
            chunk,
        ) ?: chunk()
        require(chunks.isNotEmpty()) { "The Cited Paper parser returned no usable section paragraphs." }
        val provider = embeddingProviders.singleOrNull {
            it.providerId == profile.providerId && it.modelId == profile.modelId && it.version == profile.version && it.dimension == profile.dimension
        } ?: throw IllegalStateException("The pinned embedding profile is unavailable.")
        var embeddingCallIndex = 0
        fun embed(text: String, category: DataCategory): FloatArray {
            repository.requireActiveRun(analysisRunId)
            val callIndex = embeddingCallIndex++
            val embedding = { provider.embed(text, EmbeddingRequestContext(context.configuration, category)) }
            val vector = executionService?.recordCurrentProviderCall(
                operationKey = "evidence-embedding-$callIndex",
                name = "Generate evidence embedding",
                providerId = profile.providerId,
                modelId = profile.modelId,
                operation = embedding,
            ) ?: embedding()
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
        val persistAndRetrieve = { repository.persistAndRetrieve(context, parsed, embeddedChunks, claimVectors, profile) }
        executionService?.record(
            analysisRunId,
            ExecutionSpanSpec("evidence", "PERSISTENCE", "Persist vectors and retrieve evidence"),
            persistAndRetrieve,
        ) ?: persistAndRetrieve()
    }
}
