package com.papertrail.api.scholarly.references.http

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "One supported Crossref cache lookup type. This API cannot address arbitrary Redis keys or commands.")
enum class CrossrefCacheLookupType {
    DOI,
    SEARCH,
}
