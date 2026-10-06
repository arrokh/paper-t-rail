package com.papertrail.api.infrastructure.providers.http

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "Stable data category and its user-facing disclosure.")
data class DataCategoryDisclosure(
    @field:Schema(description = "Stable identifier used in provider consent and configuration.")
    val id: String,
    @field:Schema(description = "Short display label.")
    val label: String,
    @field:Schema(description = "Description of the data represented by this category.")
    val description: String,
)
