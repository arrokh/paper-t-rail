package com.papertrail.api.analysis.recovery.service

import com.papertrail.api.analysis.recovery.domain.RecoveryUploadIdentityPolicy
import com.papertrail.api.analysis.recovery.domain.RecoveryUploadValidationAttempt
import com.papertrail.api.analysis.recovery.domain.RecoveryUploadValidationContext
import com.papertrail.api.analysis.recovery.domain.RecoveryValidationStatus
import com.papertrail.api.analysis.recovery.domain.languageEligibilityPolicy
import com.papertrail.api.external.docling.DoclingCitedPaperPdfParser

internal object RecoveryUploadValidationCompatibility {
    fun matchesCurrentPolicy(
        attempt: RecoveryUploadValidationAttempt,
        context: RecoveryUploadValidationContext,
    ): Boolean = attempt.identityPolicyVersion == RecoveryUploadIdentityPolicy.VERSION &&
        attempt.matchesLanguagePolicy(context.languageEligibilityPolicy()) &&
        matchesParserPolicy(attempt, context) &&
        (attempt.validationStatus != RecoveryValidationStatus.COMPLETED || attempt.contentSha256 == context.upload.actualSha256)

    fun matchesParserPolicy(
        attempt: RecoveryUploadValidationAttempt,
        context: RecoveryUploadValidationContext,
    ): Boolean {
        val parserSelection = context.configuration.citedPaperParserSelection()
        return attempt.parserId == parserSelection.provider &&
            attempt.parserVersion == parserSelection.version &&
            attempt.metadataExtractionPolicyVersion == DoclingCitedPaperPdfParser.METADATA_EXTRACTION_POLICY_VERSION &&
            attempt.parserOptions == DoclingCitedPaperPdfParser.BIBLIOGRAPHIC_METADATA_EXTRACTION_OPTIONS
    }

    fun canReuse(
        attempt: RecoveryUploadValidationAttempt,
        context: RecoveryUploadValidationContext,
        contentSha256: String,
    ): Boolean = attempt.validationStatus == RecoveryValidationStatus.COMPLETED &&
        attempt.contentSha256 == contentSha256 &&
        matchesCurrentPolicy(attempt, context)
}
