package com.papertrail.api.analysis.configuration

import com.papertrail.api.infrastructure.providers.ProviderTrustBoundary

data class ProviderSelection(
    val provider: String,
    val version: String,
    val model: String? = null,
    val trustBoundary: String = ProviderTrustBoundary.LOCAL.id,
    val dataCategories: List<String> = emptyList(),
    val configurationFingerprint: String? = null,
    val embeddingDimension: Int? = null,
)
