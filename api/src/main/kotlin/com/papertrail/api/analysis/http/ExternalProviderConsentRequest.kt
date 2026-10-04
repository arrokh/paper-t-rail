package com.papertrail.api.analysis.http

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "Client confirmation of the exact provider disclosure returned by the server and the requested data categories. The disclosure text itself is resolved and snapshotted server-side.")
data class ExternalProviderConsentRequest(
    @field:Schema(description = "Stable provider identifier selected for this Analysis Run.", requiredMode = Schema.RequiredMode.REQUIRED)
    val providerId: String,
    @field:Schema(description = "Exact server-declared data-category IDs approved for this Analysis Run.", requiredMode = Schema.RequiredMode.REQUIRED)
    val dataCategories: List<String>,
    @field:Schema(description = "Opaque server-issued fingerprint for the retention disclosure displayed before this consent. It is validated, not stored as the disclosure.", requiredMode = Schema.RequiredMode.REQUIRED, pattern = "^[0-9a-f]{64}$")
    val retentionDisclosureFingerprint: String,
)
