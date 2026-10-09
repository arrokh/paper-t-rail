package com.papertrail.api.analysis.recovery.http

import com.papertrail.api.analysis.recovery.domain.RecoveryUploadValidationAttempt
import com.papertrail.api.citation.parsing.ParsedBibliographicMetadataCandidate
import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant
import java.util.UUID

@Schema(description = "Persisted local validation attempt. Identity, language eligibility, file validity, and evidence assessment are separate outcomes.")
data class RecoveryUploadValidationResponse(
    val id: UUID,
    val batchId: UUID,
    val uploadId: UUID,
    val analysisRunId: UUID,
    val contentSha256: String,
    val parserId: String,
    val parserVersion: String,
    val metadataExtractionPolicyVersion: String,
    @field:Schema(description = "Effective local Docling conversion options recorded for this attempt.")
    val parserOptions: Map<String, String>,
    val languageDetectorId: String,
    val languageDetectorVersion: String,
    val minimumLanguageConfidence: Double,
    @field:Schema(description = "Attempt execution state; this does not indicate identity or evidence assessment.", allowableValues = ["COMPLETED", "FAILED"])
    val validationStatus: String,
    @field:Schema(description = "Machine identity result only; a human confirmation is recorded separately.", nullable = true, allowableValues = ["VALIDATED", "NEEDS_CONFIRMATION", "MISMATCH"])
    val identityOutcome: String?,
    val identityReasonCode: String?,
    @field:Schema(description = "Separate human confirmation for this exact validation attempt; null means no human confirmation was recorded.", nullable = true)
    val humanConfirmation: RecoveryIdentityConfirmationResponse?,
    @field:Schema(description = "Explicitly selected exact asset version; null means no version is selected for this entry.", nullable = true)
    val selection: RecoveryUploadAssetSelectionResponse?,
    @field:Schema(description = "Docling-extracted bibliographic text and source provenance; never includes full document text.")
    val metadataCandidates: List<ParsedBibliographicMetadataCandidate>,
    @field:Schema(description = "Language eligibility under the immutable Analysis Run's pinned detector policy.", allowableValues = ["ELIGIBLE", "INELIGIBLE", "INDETERMINATE"])
    val languageEligibility: String,
    val detectedLanguage: String?,
    val languageConfidence: Double?,
    val languageReasonCode: String,
    val failureCode: String?,
    val createdAt: Instant,
) {
    companion object {
        fun from(
            attempt: RecoveryUploadValidationAttempt,
            humanConfirmation: RecoveryIdentityConfirmationResponse? = null,
            selection: RecoveryUploadAssetSelectionResponse? = null,
        ) = RecoveryUploadValidationResponse(
            id = attempt.id,
            batchId = attempt.batchId,
            uploadId = attempt.uploadId,
            analysisRunId = attempt.analysisRunId,
            contentSha256 = attempt.contentSha256,
            parserId = attempt.parserId,
            parserVersion = attempt.parserVersion,
            metadataExtractionPolicyVersion = attempt.metadataExtractionPolicyVersion,
            parserOptions = attempt.parserOptions,
            languageDetectorId = attempt.languageDetectorId,
            languageDetectorVersion = attempt.languageDetectorVersion,
            minimumLanguageConfidence = attempt.minimumLanguageConfidence,
            validationStatus = attempt.validationStatus.name,
            identityOutcome = attempt.identityOutcome?.name,
            identityReasonCode = attempt.identityReasonCode,
            humanConfirmation = humanConfirmation,
            selection = selection,
            metadataCandidates = attempt.metadataCandidates,
            languageEligibility = attempt.languageEligibility.name,
            detectedLanguage = attempt.detectedLanguage,
            languageConfidence = attempt.languageConfidence,
            languageReasonCode = attempt.languageReasonCode,
            failureCode = attempt.failureCode,
            createdAt = attempt.createdAt,
        )
    }
}
