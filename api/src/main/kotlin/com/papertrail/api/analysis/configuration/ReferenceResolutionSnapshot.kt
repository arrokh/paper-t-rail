package com.papertrail.api.analysis.configuration

data class ReferenceResolutionSnapshot(
    val executionStatus: String = "NOT_RUN",
    val provider: ProviderSelection? = null,
    val scorePolicyVersion: String? = null,
    val confidenceThreshold: Double? = null,
    val providerConfigurationFingerprint: String? = null,
)
