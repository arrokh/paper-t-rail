package com.papertrail.api.analysis.recovery.service

import com.papertrail.api.analysis.configuration.AnalysisConfigurationSnapshot
import com.papertrail.api.analysis.configuration.ProviderSelection
import com.papertrail.api.analysis.configuration.ValidationLimitsSnapshot
import com.papertrail.api.analysis.recovery.domain.RecoveryIdentityOutcome
import com.papertrail.api.analysis.recovery.domain.RecoveryLanguageEligibility
import com.papertrail.api.analysis.recovery.domain.RecoveryLanguageEligibilityPolicy
import com.papertrail.api.analysis.recovery.domain.RecoveryUpload
import com.papertrail.api.analysis.recovery.domain.RecoveryUploadIdentityPolicy
import com.papertrail.api.analysis.recovery.domain.RecoveryUploadStatus
import com.papertrail.api.analysis.recovery.domain.RecoveryUploadValidationAttempt
import com.papertrail.api.analysis.recovery.domain.RecoveryUploadValidationContext
import com.papertrail.api.analysis.recovery.domain.RecoveryValidationStatus
import com.papertrail.api.analysis.recovery.repository.RecoveryUploadValidationRepository
import com.papertrail.api.citation.parsing.BibliographyNormalizationPolicySelection
import com.papertrail.api.citation.parsing.ParsedBibliographicMetadataCandidate
import com.papertrail.api.citation.parsing.ParsedBibliographicMetadataExtractionMethod
import com.papertrail.api.citation.parsing.ParsedBibliographicMetadataField
import com.papertrail.api.citation.parsing.ParsedScientificDocument
import com.papertrail.api.document.validation.DocumentLanguageDetector
import com.papertrail.api.document.validation.LanguageDetection
import com.papertrail.api.evidence.parsing.CitedPaperPdfParser
import com.papertrail.api.external.docling.DoclingCitedPaperPdfParser
import com.papertrail.api.infrastructure.crypto.sha256Hex
import com.papertrail.api.infrastructure.storage.SourceDocumentObjectStore
import com.papertrail.api.scholarly.references.client.BibliographyReference
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

class RecoveryUploadValidationServiceTest {
    @Test
    fun `reparses an upload when the language policy or persisted parser inputs differ`() {
        val batchId = UUID.randomUUID()
        val uploadId = UUID.randomUUID()
        val runId = UUID.randomUUID()
        val entryId = UUID.randomUUID()
        val bytes = "%PDF-1.7 validation fixture".toByteArray(Charsets.US_ASCII)
        val contentSha256 = sha256Hex(bytes)
        val sourceText = "English paper text with enough words for language detection. ".repeat(3)
        val configuration = configuration(minimumExtractedCharacters = sourceText.length + 1)
        val upload = RecoveryUpload(
            id = uploadId,
            batchId = batchId,
            analysisRunId = runId,
            bibliographyEntryId = entryId,
            localReferenceKey = "b0",
            idempotencyKey = UUID.randomUUID(),
            filename = "paper.pdf",
            expectedSize = bytes.size.toLong(),
            expectedSha256 = contentSha256,
            stagingObjectKey = "staging/$uploadId",
            finalizedObjectKey = "finalized/$uploadId",
            status = RecoveryUploadStatus.STAGED,
            failureCode = null,
            presignedUrlExpiresAt = Instant.now().plusSeconds(300),
            actualSize = bytes.size.toLong(),
            actualSha256 = contentSha256,
            createdAt = Instant.now(),
            updatedAt = Instant.now(),
            finalizedAt = Instant.now(),
        )
        val title = "A Cited Paper"
        val reference = BibliographyReference(
            title = title,
            authors = listOf("A. Author"),
            year = 2025,
            doi = "10.5555/papertrail.language-policy",
            referenceType = "JOURNAL_ARTICLE",
        )
        val context = RecoveryUploadValidationContext(
            upload = upload,
            referenceType = "JOURNAL_ARTICLE",
            resolvedReference = reference,
            configuration = configuration,
        )
        val priorAttempt = RecoveryUploadValidationAttempt(
            id = UUID.randomUUID(),
            batchId = batchId,
            uploadId = uploadId,
            analysisRunId = runId,
            contentSha256 = contentSha256,
            parserId = "docling",
            parserVersion = "1.30.0",
            metadataExtractionPolicyVersion = DoclingCitedPaperPdfParser.METADATA_EXTRACTION_POLICY_VERSION,
            identityPolicyVersion = RecoveryUploadIdentityPolicy.VERSION,
            parserOptions = DoclingCitedPaperPdfParser.BIBLIOGRAPHIC_METADATA_EXTRACTION_OPTIONS,
            languageDetectorId = "optimaize",
            languageDetectorVersion = "0.6",
            minimumLanguageConfidence = configuration.validationLimits.minimumLanguageConfidence,
            minimumExtractedCharacters = 100,
            languagePolicyFingerprint = RecoveryLanguageEligibilityPolicy("optimaize", "0.6", 100, 0.65).fingerprint,
            validationStatus = RecoveryValidationStatus.COMPLETED,
            identityOutcome = RecoveryIdentityOutcome.VALIDATED,
            identityReasonCode = "DOI_MATCH",
            metadataCandidates = emptyList(),
            languageEligibility = RecoveryLanguageEligibility.ELIGIBLE,
            detectedLanguage = "en",
            languageConfidence = 0.99,
            languageReasonCode = "ENGLISH_DETECTED",
            failureCode = null,
            createdAt = Instant.now(),
        )
        val repository = Mockito.mock(RecoveryUploadValidationRepository::class.java)
        Mockito.`when`(
            repository.validationContext(
                anyUuid(),
                anyUuid(),
                anyInstant(),
            ),
        ).thenReturn(context)
        Mockito.`when`(
            repository.latestAttempt(
                anyUuid(),
                anyUuid(),
                anyInstant(),
            ),
        ).thenReturn(priorAttempt)
        val objectStore = Mockito.mock(SourceDocumentObjectStore::class.java)
        Mockito.`when`(objectStore.get(upload.finalizedObjectKey)).thenReturn(bytes)
        val parserCalls = AtomicInteger()
        val parser = object : CitedPaperPdfParser {
            override val parserId = "docling"

            override fun parse(pdf: ByteArray): ParsedScientificDocument {
                parserCalls.incrementAndGet()
                val doi = requireNotNull(reference.doi)
                return ParsedScientificDocument(
                    parserId = parserId,
                    parserVersion = "1.30.0",
                    normalizedSourceText = sourceText,
                    sections = emptyList(),
                    citationContexts = emptyList(),
                    bibliographyEntries = emptyList(),
                    bibliographicMetadataCandidates = listOf(
                        ParsedBibliographicMetadataCandidate(
                            field = ParsedBibliographicMetadataField.TITLE,
                            value = title,
                            pageNumber = 1,
                            sourceLabel = "title",
                            extractionMethod = ParsedBibliographicMetadataExtractionMethod.DOCLING_LABEL,
                            sourceElementId = "docling-title-0",
                            sourceCharSpanStart = 0,
                            sourceCharSpanEnd = title.length,
                        ),
                        ParsedBibliographicMetadataCandidate(
                            field = ParsedBibliographicMetadataField.DOI,
                            value = doi,
                            pageNumber = 1,
                            sourceLabel = "doi",
                            extractionMethod = ParsedBibliographicMetadataExtractionMethod.EXPLICIT_DOI_PREFIX,
                            sourceElementId = "docling-doi-0",
                            sourceCharSpanStart = 0,
                            sourceCharSpanEnd = doi.length,
                        ),
                    ),
                    bibliographicMetadataExtractionPolicyVersion = DoclingCitedPaperPdfParser.METADATA_EXTRACTION_POLICY_VERSION,
                    bibliographicMetadataExtractionOptions = DoclingCitedPaperPdfParser.BIBLIOGRAPHIC_METADATA_EXTRACTION_OPTIONS,
                )
            }
        }
        val service = RecoveryUploadValidationService(
            repository = repository,
            objectStore = objectStore,
            parser = parser,
            languageDetector = object : DocumentLanguageDetector {
                override fun detect(text: String) = LanguageDetection("en", 0.99)
            },
        )

        val validated = service.validate(batchId, uploadId)

        assertNotEquals(priorAttempt.id, validated.id)
        assertEquals(1, parserCalls.get())
        assertEquals(RecoveryLanguageEligibility.INDETERMINATE, validated.languageEligibility)
        assertEquals("INSUFFICIENT_EXTRACTED_TEXT", validated.languageReasonCode)
        assertEquals(sourceText.length + 1, validated.minimumExtractedCharacters)
        assertNotEquals(priorAttempt.languagePolicyFingerprint, validated.languagePolicyFingerprint)
        assertEquals(
            RecoveryLanguageEligibilityPolicy("optimaize", "0.6", sourceText.length + 1, 0.65).fingerprint,
            validated.languagePolicyFingerprint,
        )
        assertNull(service.latest(batchId, uploadId))

        val currentPolicy = RecoveryLanguageEligibilityPolicy("optimaize", "0.6", sourceText.length + 1, 0.65)
        val compatibleLanguagePolicyAttempt = priorAttempt.copy(
            minimumExtractedCharacters = sourceText.length + 1,
            languagePolicyFingerprint = currentPolicy.fingerprint,
        )
        val incompatibleParserInputs = listOf(
            compatibleLanguagePolicyAttempt.copy(contentSha256 = "f".repeat(64)),
            compatibleLanguagePolicyAttempt.copy(parserVersion = "1.29.0"),
            compatibleLanguagePolicyAttempt.copy(metadataExtractionPolicyVersion = "docling-first-page-metadata-candidates-v1"),
            compatibleLanguagePolicyAttempt.copy(parserOptions = mapOf("do_ocr" to "true")),
        )
        incompatibleParserInputs.forEachIndexed { index, attempt ->
            Mockito.`when`(
                repository.latestAttempt(
                    anyUuid(),
                    anyUuid(),
                    anyInstant(),
                ),
            ).thenReturn(attempt)

            val reparsed = service.validate(batchId, uploadId)

            assertNotEquals(attempt.id, reparsed.id)
            assertEquals(contentSha256, reparsed.contentSha256)
            assertEquals("1.30.0", reparsed.parserVersion)
            assertEquals(DoclingCitedPaperPdfParser.BIBLIOGRAPHIC_METADATA_EXTRACTION_OPTIONS, reparsed.parserOptions)
            assertEquals(index + 2, parserCalls.get())
            assertNull(service.latest(batchId, uploadId))
        }
    }

    private fun anyInstant(): Instant = Mockito.any(Instant::class.java) ?: Instant.EPOCH

    private fun anyUuid(): UUID = Mockito.any(UUID::class.java) ?: UUID(0L, 0L)

    private fun configuration(minimumExtractedCharacters: Int) = AnalysisConfigurationSnapshot(
        claimExtractor = ProviderSelection("heuristic", "v1"),
        embedding = ProviderSelection("local", "v1"),
        systemOne = ProviderSelection("mock", "v1"),
        sourceParser = ProviderSelection("grobid", "0.9.1-crf"),
        languageDetector = ProviderSelection("optimaize", "0.6"),
        validationLimits = ValidationLimitsSnapshot(
            maxUploadBytes = 1_000_000,
            maxPages = 10,
            maxExtractedCharacters = 100_000,
            maxExtractedCharactersPerPage = 20_000,
            minimumExtractedCharacters = minimumExtractedCharacters,
            minimumLanguageConfidence = 0.65,
        ),
        externalProviderConsents = emptyList(),
        citedPaperParser = ProviderSelection("docling", "1.30.0"),
        bibliographyNormalizationPolicy = BibliographyNormalizationPolicySelection.CURRENT,
    )
}
