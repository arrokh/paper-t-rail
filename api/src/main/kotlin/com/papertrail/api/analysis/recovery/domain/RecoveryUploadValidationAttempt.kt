package com.papertrail.api.analysis.recovery.domain

import com.papertrail.api.citation.parsing.ParsedBibliographicMetadataCandidate
import java.time.Instant
import java.util.UUID

data class RecoveryUploadValidationAttempt(
    val id: UUID,
    val batchId: UUID,
    val uploadId: UUID,
    val analysisRunId: UUID,
    val contentSha256: String,
    val parserId: String,
    val parserVersion: String,
    val metadataExtractionPolicyVersion: String,
    val identityPolicyVersion: String,
    val parserOptions: Map<String, String>,
    val languageDetectorId: String,
    val languageDetectorVersion: String,
    val minimumLanguageConfidence: Double,
    val minimumExtractedCharacters: Int? = null,
    val languagePolicyFingerprint: String? = null,
    val validationStatus: RecoveryValidationStatus,
    val identityOutcome: RecoveryIdentityOutcome?,
    val identityReasonCode: String?,
    val metadataCandidates: List<ParsedBibliographicMetadataCandidate>,
    val languageEligibility: RecoveryLanguageEligibility,
    val detectedLanguage: String?,
    val languageConfidence: Double?,
    val languageReasonCode: String,
    val failureCode: String?,
    val createdAt: Instant,
) {
    fun matchesLanguagePolicy(policy: RecoveryLanguageEligibilityPolicy): Boolean =
        languageDetectorId == policy.detectorId &&
            languageDetectorVersion == policy.detectorVersion &&
            minimumLanguageConfidence == policy.minimumConfidence &&
            minimumExtractedCharacters == policy.minimumExtractedCharacters &&
            languagePolicyFingerprint == policy.fingerprint
}
