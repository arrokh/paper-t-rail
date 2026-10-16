package com.papertrail.api.scholarly.references.report

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "Content-free provider lookup record. It never contains the bibliography query or provider response body.")
data class ReferenceResolutionProviderLookupReport(
    @field:Schema(description = "Provider selected by the pinned chain.", allowableValues = ["crossref", "datacite"])
    val providerId: String,
    @field:Schema(description = "Fixed position in the approved chain.", allowableValues = ["1", "2"])
    val lookupOrdinal: Int,
    @field:Schema(description = "Whether this logical result came from the provider wire or the consent-scoped Crossref cache.", allowableValues = ["WIRE", "CACHE"])
    val source: String,
    @field:Schema(description = "Content-free provider result code.", allowableValues = ["FOUND", "NOT_FOUND", "UNUSABLE", "RETRYABLE_FAILURE", "PERMANENT_FAILURE"])
    val outcomeCode: String,
)
