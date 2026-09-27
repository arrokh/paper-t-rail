package com.papertrail.api.scholarly.acquisition.http

import io.swagger.v3.oas.annotations.media.Schema

data class UnpaywallCacheInvalidationRequest(
    @field:Schema(description = "DOI identifier or DOI URL for the one Unpaywall discovery entry to invalidate.", example = "10.1234/example")
    val doi: String,
)
