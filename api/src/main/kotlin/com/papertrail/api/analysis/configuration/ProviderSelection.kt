package com.papertrail.api.analysis.configuration

import com.fasterxml.jackson.annotation.JsonInclude
import com.papertrail.api.infrastructure.providers.ProviderTrustBoundary
import io.swagger.v3.oas.annotations.media.Schema

data class ProviderSelection(
    val provider: String,
    val version: String,
    val model: String? = null,
    val trustBoundary: String = ProviderTrustBoundary.LOCAL.id,
    val dataCategories: List<String> = emptyList(),
    val configurationFingerprint: String? = null,
    val embeddingDimension: Int? = null,
    val retentionDisclosure: String? = null,
    val targetSelectionPolicyVersion: String? = null,
    val promptVersion: String? = null,
    val outputMappingVersion: String? = null,
    @field:Schema(description = "Opaque server-computed fingerprint for the pinned external-provider disclosure, when applicable.")
    @field:JsonInclude(JsonInclude.Include.NON_NULL)
    val retentionDisclosureFingerprint: String? = null,
)
