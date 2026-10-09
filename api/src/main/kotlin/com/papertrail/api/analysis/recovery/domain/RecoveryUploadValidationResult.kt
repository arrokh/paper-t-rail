package com.papertrail.api.analysis.recovery.domain

import com.papertrail.api.citation.parsing.ParsedBibliographicMetadataCandidate

/** Identity, language, parser, and file validity remain independent outcomes. */
data class RecoveryUploadValidationResult(
    val identityOutcome: RecoveryIdentityOutcome?,
    val identityReasonCode: String?,
    val languageEligibility: RecoveryLanguageEligibility,
    val detectedLanguage: String?,
    val languageConfidence: Double?,
    val languageReasonCode: String,
    val metadataCandidates: List<ParsedBibliographicMetadataCandidate>,
    val validationStatus: RecoveryValidationStatus,
    val failureCode: String? = null,
)
