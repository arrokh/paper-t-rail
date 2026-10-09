package com.papertrail.api.scholarly.acquisition.service

import com.papertrail.api.analysis.configuration.AnalysisConfigurationSnapshot
import com.papertrail.api.scholarly.acquisition.domain.AcquiredFullText
import com.papertrail.api.scholarly.acquisition.repository.ResolvedCitedReference

fun interface CitedPaperIdentityValidator {
    fun validate(
        fullText: AcquiredFullText,
        reference: ResolvedCitedReference,
        configuration: AnalysisConfigurationSnapshot,
    ): CitedPaperIdentityValidationResult
}
