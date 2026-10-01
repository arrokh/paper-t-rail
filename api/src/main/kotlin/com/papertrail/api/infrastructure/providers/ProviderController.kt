package com.papertrail.api.infrastructure.providers

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/providers", produces = [MediaType.APPLICATION_JSON_VALUE])
@Tag(name = "Providers", description = "Discover selectable providers and their data disclosures.")
class ProviderController(private val providerCatalog: ProviderCatalog) {
    @Operation(
        summary = "List selectable providers",
        description = "Returns enabled, classified providers grouped by role, along with the stable data-category disclosure catalog. Provider URLs, credentials, and configuration fingerprints are not exposed.",
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "Selectable providers and data-category disclosures",
                content = [Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = Schema(implementation = ProviderDirectoryResponse::class),
                )],
            ),
        ],
    )
    @GetMapping
    fun listProviders() = providerCatalog.directory()
}
