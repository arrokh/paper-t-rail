package com.papertrail.api.analysis.configuration

data class ExternalProviderConsentSnapshot(
    val providerId: String,
    val dataCategories: List<String>,
)
