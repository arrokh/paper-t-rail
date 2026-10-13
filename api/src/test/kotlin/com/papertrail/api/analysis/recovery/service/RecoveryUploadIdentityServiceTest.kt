package com.papertrail.api.analysis.recovery.service

import com.papertrail.api.analysis.configuration.AnalysisConfigurationSnapshot
import com.papertrail.api.analysis.configuration.ProviderSelection
import com.papertrail.api.analysis.configuration.ValidationLimitsSnapshot
import com.papertrail.api.analysis.recovery.domain.RecoveryIdentityOutcome
import com.papertrail.api.analysis.recovery.domain.RecoveryLanguageEligibility
import com.papertrail.api.analysis.recovery.domain.RecoveryLanguageEligibilityPolicy
import com.papertrail.api.analysis.recovery.domain.RecoveryStagingException
import com.papertrail.api.analysis.recovery.domain.RecoveryUpload
import com.papertrail.api.analysis.recovery.domain.RecoveryUploadIdentityPolicy
import com.papertrail.api.analysis.recovery.domain.RecoveryUploadStatus
import com.papertrail.api.analysis.recovery.domain.RecoveryUploadValidationAttempt
import com.papertrail.api.analysis.recovery.domain.RecoveryUploadValidationContext
import com.papertrail.api.analysis.recovery.domain.RecoveryValidationStatus
import com.papertrail.api.analysis.recovery.repository.RecoveryUploadIdentityDecisionRepository
import com.papertrail.api.analysis.recovery.repository.RecoveryUploadValidationRepository
import com.papertrail.api.citation.parsing.BibliographyNormalizationPolicySelection
import com.papertrail.api.citation.parsing.ParsedBibliographicMetadataCandidate
import com.papertrail.api.external.docling.DoclingCitedPaperPdfParser
import com.papertrail.api.infrastructure.crypto.sha256Hex
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.time.Instant
import java.util.UUID

class RecoveryUploadIdentityServiceTest {
    @Test
    fun `rejects selection and confirmation when persisted policy or hash is stale`() {
        val batchId = UUID.randomUUID()
        val uploadId = UUID.randomUUID()
        val runId = UUID.randomUUID()
        val entryId = UUID.randomUUID()
        val bytes = "%PDF-1.7 selection fixture".toByteArray(Charsets.US_ASCII)
        val contentSha256 = sha256Hex(bytes)
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
        val configuration = configuration(minimumExtractedCharacters = 200)
        val context = RecoveryUploadValidationContext(
            upload = upload,
            referenceType = "JOURNAL_ARTICLE",
            resolvedReference = null,
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
            minimumLanguageConfidence = 0.65,
            minimumExtractedCharacters = 100,
            languagePolicyFingerprint = RecoveryLanguageEligibilityPolicy("optimaize", "0.6", 100, 0.65).fingerprint,
            validationStatus = RecoveryValidationStatus.COMPLETED,
            identityOutcome = RecoveryIdentityOutcome.VALIDATED,
            identityReasonCode = "DOI_MATCH",
            metadataCandidates = emptyList<ParsedBibliographicMetadataCandidate>(),
            languageEligibility = RecoveryLanguageEligibility.ELIGIBLE,
            detectedLanguage = "en",
            languageConfidence = 0.99,
            languageReasonCode = "ENGLISH_DETECTED",
            failureCode = null,
            createdAt = Instant.now(),
        )
        val validationRepository = Mockito.mock(RecoveryUploadValidationRepository::class.java)
        Mockito.`when`(
            validationRepository.validationContext(anyUuid(), anyUuid(), anyInstant()),
        ).thenReturn(context)
        Mockito.`when`(
            validationRepository.latestAttempt(anyUuid(), anyUuid(), anyInstant()),
        ).thenReturn(priorAttempt)
        val decisions = Mockito.mock(RecoveryUploadIdentityDecisionRepository::class.java)
        val service = RecoveryUploadIdentityService(validationRepository, decisions)

        val rejection = assertThrows(RecoveryStagingException::class.java) {
            service.selectExactVersion(batchId, uploadId)
        }

        assertEquals("RECOVERY_LANGUAGE_POLICY_STALE", rejection.code)

        val staleParserAttempt = priorAttempt.copy(
            minimumExtractedCharacters = 200,
            languagePolicyFingerprint = RecoveryLanguageEligibilityPolicy("optimaize", "0.6", 200, 0.65).fingerprint,
            parserOptions = mapOf("do_ocr" to "true"),
        )
        Mockito.`when`(
            validationRepository.latestAttempt(anyUuid(), anyUuid(), anyInstant()),
        ).thenReturn(staleParserAttempt)
        val parserPolicyRejection = assertThrows(RecoveryStagingException::class.java) {
            service.selectExactVersion(batchId, uploadId)
        }

        assertEquals("RECOVERY_VALIDATION_POLICY_STALE", parserPolicyRejection.code)

        val staleHashAttempt = priorAttempt.copy(
            id = UUID.randomUUID(),
            contentSha256 = "f".repeat(64),
            identityOutcome = RecoveryIdentityOutcome.NEEDS_CONFIRMATION,
            minimumExtractedCharacters = 200,
            languagePolicyFingerprint = RecoveryLanguageEligibilityPolicy("optimaize", "0.6", 200, 0.65).fingerprint,
        )
        Mockito.`when`(
            validationRepository.latestAttempt(anyUuid(), anyUuid(), anyInstant()),
        ).thenReturn(staleHashAttempt)
        val hashRejection = assertThrows(RecoveryStagingException::class.java) {
            service.confirmExactVersion(batchId, uploadId, staleHashAttempt.id, true)
        }

        assertEquals("RECOVERY_VALIDATION_STALE", hashRejection.code)
        Mockito.verifyNoInteractions(decisions)
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
