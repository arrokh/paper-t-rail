package com.papertrail.api.scholarly.acquisition.domain

import java.time.Instant
import java.util.UUID

data class UserSuppliedCitedPaperProvenance(
    val contentSha256: String,
    val mediaType: String,
    val language: String,
    val languageDetectorVersion: String,
    val rightsDeclarationVersion: String,
    val rightsDeclarationText: String,
    val rightsDeclaredAt: Instant,
    val originatingAnalysisRunId: UUID,
    val originatingRecoveryBatchId: UUID,
    val originatingRecoveryUploadId: UUID,
) {
    init {
        require(contentSha256.matches(Regex("^[0-9a-f]{64}$")))
        require(mediaType.equals("application/pdf", ignoreCase = true))
        require(language.isNotBlank())
        require(languageDetectorVersion.isNotBlank())
        require(rightsDeclarationVersion.isNotBlank())
        require(rightsDeclarationText.isNotBlank())
    }
}
