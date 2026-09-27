package com.papertrail.api.scholarly.references.http

import io.swagger.v3.oas.annotations.media.Schema

data class CrossrefCacheInvalidationRequest(
    @field:Schema(description = "The one Crossref logical lookup to invalidate.")
    val lookupType: CrossrefCacheLookupType,
    @field:Schema(description = "DOI identifier or DOI URL; required only for DOI invalidation.", example = "10.1234/example")
    val doi: String? = null,
    @field:Schema(description = "The bibliographic search text used for the lookup; required only for search invalidation.")
    val query: String? = null,
)
