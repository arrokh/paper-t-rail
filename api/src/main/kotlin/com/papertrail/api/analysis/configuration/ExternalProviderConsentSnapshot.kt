package com.papertrail.api.analysis.configuration

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "Immutable record of consent captured for an external provider when this Analysis Run was created. The disclosure text is server-resolved and retained verbatim.")
data class ExternalProviderConsentSnapshot(
    @field:Schema(description = "External provider identifier selected for the run.")
    val providerId: String,
    @field:Schema(description = "Exact stable data-category IDs authorized for this provider.")
    val dataCategories: List<String>,
    @field:Schema(description = "Exact retention/deletion disclosure shown at consent time. Null only for legacy runs created before disclosure snapshots were introduced.")
    val retentionDisclosure: String? = null,
)
