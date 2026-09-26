package com.papertrail.api.analysis.configuration

data class AnalysisConfigurationSnapshot(
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
)
