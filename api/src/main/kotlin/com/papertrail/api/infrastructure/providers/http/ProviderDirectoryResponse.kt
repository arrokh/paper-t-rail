package com.papertrail.api.infrastructure.providers.http

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "Selectable providers grouped by role and the catalog of data categories used for disclosure and consent.")
data class ProviderDirectoryResponse(
    @field:Schema(description = "Enabled provider options grouped by their role identifier.")
    val providers: Map<String, List<ProviderOption>>,
    @field:Schema(description = "Stable data-category identifiers and descriptions.")
    val dataCategories: List<DataCategoryDisclosure>,
)
