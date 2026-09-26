package com.papertrail.api.analysis.configuration

data class AnalysisConfigurationSnapshot(
    val claimExtractor: ProviderSelection,
    val embedding: ProviderSelection,
    val systemOne: ProviderSelection,
    val sourceParser: ProviderSelection,
    val languageDetector: ProviderSelection,
    val validationLimits: ValidationLimitsSnapshot,
    val referenceResolution: ReferenceResolutionSnapshot = ReferenceResolutionSnapshot(),
    val openAccess: ProviderSelection = ProviderSelection("recorded-fixtures", "v1"),
    val openAccessProviderConfigurationFingerprint: String? = null,
    val openAccessRetentionDisclosure: String? = null,
    val aggregation: AggregationPolicySnapshot,
    val externalProviderConsents: List<ExternalProviderConsentSnapshot>,
)
