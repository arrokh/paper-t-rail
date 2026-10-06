package com.papertrail.api.external.unpaywall.http

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "Whether the requested provider-cache entry existed and was deleted.")
data class CacheInvalidationResponse(
    val invalidated: Boolean,
)
