package com.papertrail.api.analysis.recovery.service

import com.papertrail.api.analysis.recovery.domain.RecoveryLanguageEligibility
import com.papertrail.api.analysis.recovery.domain.RecoveryStagingException
import com.papertrail.api.analysis.recovery.domain.RecoveryUploadIdentityPolicy
import com.papertrail.api.analysis.recovery.domain.RecoveryUploadValidationAttempt
import com.papertrail.api.analysis.recovery.domain.RecoveryUploadValidationContext
import com.papertrail.api.analysis.recovery.domain.RecoveryUploadValidationResult
import com.papertrail.api.analysis.recovery.domain.RecoveryValidationStatus
import com.papertrail.api.analysis.recovery.repository.RecoveryUploadValidationRepository
import com.papertrail.api.document.validation.DocumentLanguageDetector
import com.papertrail.api.document.validation.OptimaizeDocumentLanguageDetector
import com.papertrail.api.evidence.parsing.CitedPaperPdfParser
import com.papertrail.api.external.docling.DoclingCitedPaperPdfParser
import com.papertrail.api.infrastructure.crypto.sha256Hex
import com.papertrail.api.infrastructure.storage.SourceDocumentObjectStore
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID

@Service
class RecoveryUploadValidationService(
    private val repository: RecoveryUploadValidationRepository,
    private val objectStore: SourceDocumentObjectStore,
    @Qualifier("doclingCitedPaperPdfParser") private val parser: CitedPaperPdfParser,
    private val languageDetector: DocumentLanguageDetector,
) {
    fun validate(batchId: UUID, uploadId: UUID): RecoveryUploadValidationAttempt {
        val context = validationContext(batchId, uploadId)
        val configuration = context.configuration
        val parserSelection = configuration.citedPaperParserSelection()
        val languageSelection = configuration.languageDetector
        val parserOptions = DoclingCitedPaperPdfParser.BIBLIOGRAPHIC_METADATA_EXTRACTION_OPTIONS
        val bytes = try {
            objectStore.get(context.upload.finalizedObjectKey)
        } catch (_: Exception) {
            return saveAttempt(
                context = context,
                contentSha256 = context.upload.actualSha256 ?: context.upload.expectedSha256,
                parserId = parserSelection.provider,
                parserVersion = parserSelection.version,
                parserOptions = parserOptions,
                result = failed("UPLOAD_BYTES_UNAVAILABLE"),
            )
        }
        val contentSha256 = sha256Hex(bytes)
        if (bytes.size.toLong() != context.upload.actualSize || contentSha256 != context.upload.actualSha256) {
            return saveAttempt(
                context = context,
                contentSha256 = contentSha256,
                parserId = parserSelection.provider,
                parserVersion = parserSelection.version,
                parserOptions = parserOptions,
                result = failed("UPLOAD_SNAPSHOT_MISMATCH"),
            )
        }
        val reusableAttempt = repository.latestAttempt(batchId, uploadId, Instant.now())
            ?.takeIf { attempt ->
                attempt.canBeReusedFor(
                    contentSha256 = contentSha256,
                    parserId = parserSelection.provider,
                    parserVersion = parserSelection.version,
                    identityPolicyVersion = RecoveryUploadIdentityPolicy.VERSION,
                    parserOptions = parserOptions,
                    languageDetectorId = languageSelection.provider,
                    languageDetectorVersion = languageSelection.version,
                    minimumLanguageConfidence = configuration.validationLimits.minimumLanguageConfidence,
                )
            }
        if (reusableAttempt != null) return reusableAttempt
        if (parserSelection.provider != parser.parserId) {
            return saveAttempt(
                context = context,
                contentSha256 = contentSha256,
                parserId = parserSelection.provider,
                parserVersion = parserSelection.version,
                parserOptions = parserOptions,
                result = failed("PINNED_CITED_PAPER_PARSER_UNAVAILABLE"),
            )
        }

        val parsed = try {
            parser.parse(bytes)
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        } catch (_: Exception) {
            null
        }
        if (parsed == null) {
            return saveAttempt(
                context = context,
                contentSha256 = contentSha256,
                parserId = parserSelection.provider,
                parserVersion = parserSelection.version,
                parserOptions = parserOptions,
                result = failed("DOCLING_PARSE_FAILED"),
            )
        }
        if (parsed.parserId != parserSelection.provider ||
            parsed.parserVersion != parserSelection.version ||
            parsed.bibliographicMetadataExtractionPolicyVersion != DoclingCitedPaperPdfParser.METADATA_EXTRACTION_POLICY_VERSION ||
            parsed.bibliographicMetadataExtractionOptions != parserOptions
        ) {
            return saveAttempt(
                context = context,
                contentSha256 = contentSha256,
                parserId = parserSelection.provider,
                parserVersion = parserSelection.version,
                parserOptions = parserOptions,
                result = failed("PARSER_PROVENANCE_MISMATCH"),
            )
        }

        val language = evaluateLanguage(
            parsed.normalizedSourceText,
            languageSelection.provider,
            languageSelection.version,
            configuration.validationLimits.minimumExtractedCharacters,
            configuration.validationLimits.minimumLanguageConfidence,
        )
        val identity = RecoveryUploadIdentityPolicy.evaluate(
            referenceType = context.referenceType,
            bibliographyPolicy = context.configuration.bibliographyNormalizationPolicy,
            reference = context.resolvedReference,
            candidates = parsed.bibliographicMetadataCandidates,
        )
        return saveAttempt(
            context = context,
            contentSha256 = contentSha256,
            parserId = parsed.parserId,
            parserVersion = parsed.parserVersion,
            parserOptions = parsed.bibliographicMetadataExtractionOptions,
            result = RecoveryUploadValidationResult(
                identityOutcome = identity.first,
                identityReasonCode = identity.second,
                languageEligibility = language.eligibility,
                detectedLanguage = language.language,
                languageConfidence = language.confidence,
                languageReasonCode = language.reasonCode,
                metadataCandidates = parsed.bibliographicMetadataCandidates,
                validationStatus = RecoveryValidationStatus.COMPLETED,
            ),
        )
    }

    fun latest(batchId: UUID, uploadId: UUID): RecoveryUploadValidationAttempt? {
        val now = Instant.now()
        validationContext(batchId, uploadId, now)
        return repository.latestAttempt(batchId, uploadId, now)
            ?.takeIf { it.identityPolicyVersion == RecoveryUploadIdentityPolicy.VERSION }
    }

    private fun validationContext(batchId: UUID, uploadId: UUID, now: Instant = Instant.now()): RecoveryUploadValidationContext =
        repository.validationContext(batchId, uploadId, now)
            ?: throw RecoveryStagingException("RECOVERY_UPLOAD_NOT_VALIDATABLE", 404, "A finalized Recovery Upload was not found.")

    private fun RecoveryUploadValidationAttempt.canBeReusedFor(
        contentSha256: String,
        parserId: String,
        parserVersion: String,
        identityPolicyVersion: String,
        parserOptions: Map<String, String>,
        languageDetectorId: String,
        languageDetectorVersion: String,
        minimumLanguageConfidence: Double,
    ): Boolean = validationStatus == RecoveryValidationStatus.COMPLETED &&
        this.contentSha256 == contentSha256 &&
        this.parserId == parserId &&
        this.parserVersion == parserVersion &&
        this.identityPolicyVersion == identityPolicyVersion &&
        metadataExtractionPolicyVersion == DoclingCitedPaperPdfParser.METADATA_EXTRACTION_POLICY_VERSION &&
        this.parserOptions == parserOptions &&
        this.languageDetectorId == languageDetectorId &&
        this.languageDetectorVersion == languageDetectorVersion &&
        this.minimumLanguageConfidence == minimumLanguageConfidence

    private fun evaluateLanguage(
        text: String,
        detectorId: String,
        detectorVersion: String,
        minimumExtractedCharacters: Int,
        minimumConfidence: Double,
    ): LanguageResult {
        if (detectorId != OptimaizeDocumentLanguageDetector.DETECTOR_ID ||
            detectorVersion != OptimaizeDocumentLanguageDetector.DETECTOR_VERSION
        ) {
            return LanguageResult(RecoveryLanguageEligibility.INDETERMINATE, null, null, "PINNED_LANGUAGE_DETECTOR_UNAVAILABLE")
        }
        if (text.length < minimumExtractedCharacters) {
            return LanguageResult(RecoveryLanguageEligibility.INDETERMINATE, null, null, "INSUFFICIENT_EXTRACTED_TEXT")
        }
        val detection = try {
            languageDetector.detect(text)
        } catch (_: Exception) {
            return LanguageResult(RecoveryLanguageEligibility.INDETERMINATE, null, null, "LANGUAGE_DETECTION_FAILED")
        }
        if (detection.language == null || detection.confidence < minimumConfidence) {
            return LanguageResult(
                RecoveryLanguageEligibility.INDETERMINATE,
                detection.language,
                detection.confidence,
                "LANGUAGE_CONFIDENCE_BELOW_RUN_MINIMUM",
            )
        }
        return if (detection.language.equals("en", ignoreCase = true)) {
            LanguageResult(RecoveryLanguageEligibility.ELIGIBLE, detection.language, detection.confidence, "ENGLISH_DETECTED")
        } else {
            LanguageResult(RecoveryLanguageEligibility.INELIGIBLE, detection.language, detection.confidence, "NON_ENGLISH_DETECTED")
        }
    }

    private fun saveAttempt(
        context: RecoveryUploadValidationContext,
        contentSha256: String,
        parserId: String,
        parserVersion: String,
        parserOptions: Map<String, String>,
        result: RecoveryUploadValidationResult,
    ): RecoveryUploadValidationAttempt {
        val attempt = RecoveryUploadValidationAttempt(
            id = UUID.randomUUID(),
            batchId = context.upload.batchId,
            uploadId = context.upload.id,
            analysisRunId = context.upload.analysisRunId,
            contentSha256 = contentSha256,
            parserId = parserId,
            parserVersion = parserVersion,
            metadataExtractionPolicyVersion = DoclingCitedPaperPdfParser.METADATA_EXTRACTION_POLICY_VERSION,
            identityPolicyVersion = RecoveryUploadIdentityPolicy.VERSION,
            parserOptions = parserOptions,
            languageDetectorId = context.configuration.languageDetector.provider,
            languageDetectorVersion = context.configuration.languageDetector.version,
            minimumLanguageConfidence = context.configuration.validationLimits.minimumLanguageConfidence,
            validationStatus = result.validationStatus,
            identityOutcome = result.identityOutcome,
            identityReasonCode = result.identityReasonCode,
            metadataCandidates = result.metadataCandidates,
            languageEligibility = result.languageEligibility,
            detectedLanguage = result.detectedLanguage,
            languageConfidence = result.languageConfidence,
            languageReasonCode = result.languageReasonCode,
            failureCode = result.failureCode,
            createdAt = Instant.now(),
        )
        repository.record(attempt)
        return attempt
    }

    private fun failed(code: String) = RecoveryUploadValidationResult(
        identityOutcome = null,
        identityReasonCode = null,
        languageEligibility = RecoveryLanguageEligibility.INDETERMINATE,
        detectedLanguage = null,
        languageConfidence = null,
        languageReasonCode = "NOT_ASSESSED_VALIDATION_FAILED",
        metadataCandidates = emptyList(),
        validationStatus = RecoveryValidationStatus.FAILED,
        failureCode = code,
    )

    private data class LanguageResult(
        val eligibility: RecoveryLanguageEligibility,
        val language: String?,
        val confidence: Double?,
        val reasonCode: String,
    )

}
