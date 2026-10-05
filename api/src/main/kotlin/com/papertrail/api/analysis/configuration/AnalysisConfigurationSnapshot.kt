package com.papertrail.api.analysis.configuration

import io.swagger.v3.oas.annotations.media.Schema

data class AnalysisConfigurationSnapshot(
    @field:Schema(description = "Original per-run choice to capture sanitized execution artifacts; stopping later capture does not change this immutable choice.")
    val captureExecution: Boolean = true,
    val claimExtractor: ProviderSelection,
    val embedding: ProviderSelection,
    val retrieval: RetrievalConfigurationSnapshot = RetrievalConfigurationSnapshot(),
    val systemOne: ProviderSelection,
    val sourceParser: ProviderSelection,
    val languageDetector: ProviderSelection,
    val validationLimits: ValidationLimitsSnapshot,
    val referenceResolution: ReferenceResolutionSnapshot = ReferenceResolutionSnapshot(),
    val openAccess: ProviderSelection = ProviderSelection("recorded-fixtures", "v1"),
    val openAccessProviderConfigurationFingerprint: String? = null,
    val openAccessRetentionDisclosure: String? = null,
    val aggregation: AggregationPolicySnapshot = AggregationPolicySnapshot(
        executionStatus = "NOT_RUN",
        verificationPolicyVersion = null,
        aggregationPolicyVersion = null,
        thresholds = null,
    ),
    val externalProviderConsents: List<ExternalProviderConsentSnapshot>,
    @field:Schema(description = "Cited Paper PDF parser pinned for Stage 04. Older Analysis Runs without this field use their source parser.")
    val citedPaperParser: ProviderSelection? = null,
) {
    fun citedPaperParserSelection(): ProviderSelection = citedPaperParser ?: sourceParser
}
