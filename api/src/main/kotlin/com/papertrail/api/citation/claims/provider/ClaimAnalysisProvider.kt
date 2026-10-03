package com.papertrail.api.citation.claims.provider

import com.papertrail.api.analysis.configuration.AnalysisConfigurationSnapshot
import com.papertrail.api.citation.claims.domain.CitationContextClaims
import com.papertrail.api.citation.claims.domain.ClaimAnalysisRequest

interface ClaimAnalysisProvider {
    val providerId: String
    val version: String
    val modelId: String?
    val targetSelectionPolicyVersion: String
    val promptVersion: String?
    val outputMappingVersion: String

    fun analyze(
        request: ClaimAnalysisRequest,
        configuration: AnalysisConfigurationSnapshot,
    ): List<CitationContextClaims>
}
