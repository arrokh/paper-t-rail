package com.papertrail.api.analysis.recovery.domain

import com.papertrail.api.analysis.configuration.AnalysisConfigurationSnapshot
import com.papertrail.api.scholarly.references.client.BibliographyReference

/** Private inputs needed to validate one staged upload against its immutable run context. */
data class RecoveryUploadValidationContext(
    val upload: RecoveryUpload,
    val referenceType: String,
    val resolvedReference: BibliographyReference?,
    val configuration: AnalysisConfigurationSnapshot,
)
