package com.papertrail.api.evidence.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.papertrail.api.analysis.configuration.RunConfigurationFactory
import com.papertrail.api.analysis.configuration.ValidationLimitsSnapshot
import com.papertrail.api.analysis.http.RunConfigurationRequest
import com.papertrail.api.citation.parsing.ParsedScientificDocument
import com.papertrail.api.citation.parsing.ParsedSection
import com.papertrail.api.document.storage.SourceDocumentObjectStore
import com.papertrail.api.evidence.chunking.SectionAwareEvidenceChunker
import com.papertrail.api.evidence.domain.EvidenceClaim
import com.papertrail.api.evidence.domain.EvidenceIndexingContext
import com.papertrail.api.evidence.embedding.EmbeddingProvider
import com.papertrail.api.evidence.embedding.EmbeddingRequestContext
import com.papertrail.api.evidence.parsing.CitedPaperParser
import com.papertrail.api.evidence.repository.EvidenceRetrievalRepository
import com.papertrail.api.infrastructure.crypto.sha256Hex
import com.papertrail.api.infrastructure.providers.DataCategory
import com.papertrail.api.infrastructure.providers.ProviderCatalog
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.util.UUID

class EvidenceRetrievalServiceTest {
    @Test
    fun `rejects query vectors with a wrong dimension before persisting any embedding`() {
        val bytes = "A cited paper section with several words.".toByteArray()
        val configuration = RunConfigurationFactory(
            objectMapper = jacksonObjectMapper(),
            providerCatalog = ProviderCatalog.safeDefaults(),
            parserId = "grobid",
            parserVersion = "0.9.1-crf",
            languageDetectorVersion = "0.6",
            limits = ValidationLimitsSnapshot(52_428_800, 500, 5_000_000, 100_000, 100, 0.65),
        ).from(RunConfigurationRequest())
        val runId = UUID.randomUUID()
        val referenceId = UUID.randomUUID()
        val context = EvidenceIndexingContext(
            analysisRunId = runId,
            bibliographyEntryId = referenceId,
            canonicalPaperId = UUID.randomUUID(),
            objectKey = "cited-paper/test",
            contentSha256 = sha256Hex(bytes),
            mediaType = "text/plain",
            language = "en",
            languageDetectorVersion = "0.6",
            configuration = configuration,
            claims = listOf(EvidenceClaim(UUID.randomUUID(), UUID.randomUUID(), "The intervention improved outcomes.")),
        )
        val repository = Mockito.mock(EvidenceRetrievalRepository::class.java)
        Mockito.`when`(repository.isCompleted(runId, referenceId)).thenReturn(false)
        Mockito.`when`(repository.loadContext(runId, referenceId)).thenReturn(context)
        var embeddingCall = 0
        val embeddingCategories = mutableListOf<DataCategory>()
        val provider = object : EmbeddingProvider {
            override val providerId = "local"
            override val modelId = "feature-hash-384-v1"
            override val version = "v1"
            override val dimension = 384

            fun embed(text: String): FloatArray {
                embeddingCall++
                return FloatArray(if (embeddingCall == 1) dimension else dimension - 1)
            }

            override fun embed(text: String, context: EmbeddingRequestContext): FloatArray {
                embeddingCategories += context.inputCategory
                return embed(text)
            }
        }
        val objectStore = object : SourceDocumentObjectStore {
            override fun put(objectKey: String, content: ByteArray, contentType: String) = Unit
            override fun get(objectKey: String): ByteArray = bytes
            override fun delete(objectKey: String) = Unit
        }
        val parser = object : CitedPaperParser {
            override fun parse(content: ByteArray, mediaType: String): ParsedScientificDocument {
                val text = content.toString(Charsets.UTF_8)
                return ParsedScientificDocument(
                    parserId = "plain-text",
                    parserVersion = "v1",
                    normalizedSourceText = text,
                    sections = listOf(ParsedSection(0, null, text, 0, text.length)),
                    citationContexts = emptyList(),
                    bibliographyEntries = emptyList(),
                )
            }
        }
        val service = EvidenceRetrievalService(
            objectStore = objectStore,
            citedPaperParser = parser,
            chunker = SectionAwareEvidenceChunker(),
            embeddingProviders = listOf(provider),
            repository = repository,
        )

        assertThrows(IllegalArgumentException::class.java) { service.retrieve(runId, referenceId) }
        assertEquals(listOf(DataCategory.CITED_PAPER_CHUNKS, DataCategory.ATOMIC_CLAIMS), embeddingCategories)
        Mockito.verify(repository).isCompleted(runId, referenceId)
        Mockito.verify(repository).loadContext(runId, referenceId)
        Mockito.verifyNoMoreInteractions(repository)
    }
}
