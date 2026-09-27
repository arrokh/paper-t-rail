package com.papertrail.api.scholarly.references.controller

import com.papertrail.api.http.ApiError
import com.papertrail.api.scholarly.references.http.CrossrefCacheInvalidationRequest
import com.papertrail.api.scholarly.references.http.CrossrefCacheInvalidationResponse
import com.papertrail.api.scholarly.references.service.CrossrefCacheInvalidationService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.enums.ParameterIn
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.parameters.RequestBody as OpenApiRequestBody
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/operator/caches/crossref")
@Tag(name = "Operator", description = "Credential-protected operational controls unavailable through the web proxy.")
class CrossrefCacheInvalidationController(
    private val service: CrossrefCacheInvalidationService,
) {
    @Operation(
        summary = "Invalidate one Crossref cache entry",
        description = "Deletes exactly one DOI or normalized bibliographic-search entry. Requires the server-side operator credential; arbitrary Redis commands and provider-wide invalidation are not supported.",
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "The selected entry was deleted, or did not exist.", content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = CrossrefCacheInvalidationResponse::class))]),
            ApiResponse(responseCode = "400", description = "The request does not identify exactly one valid logical key.", content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiError::class))]),
            ApiResponse(responseCode = "401", description = "The operator credential is missing or invalid.", content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiError::class))]),
            ApiResponse(responseCode = "503", description = "Operator authentication is not configured or Redis is unavailable.", content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiError::class))]),
        ],
    )
    @DeleteMapping(consumes = [MediaType.APPLICATION_JSON_VALUE], produces = [MediaType.APPLICATION_JSON_VALUE])
    fun invalidate(
        @Parameter(
            name = "X-Operator-Credential",
            `in` = ParameterIn.HEADER,
            required = true,
            description = "Server-side operator credential. Never put this credential in a URL or request body.",
            schema = Schema(type = "string", format = "password"),
        )
        @RequestHeader(name = "X-Operator-Credential", required = false) credential: String?,
        @OpenApiRequestBody(
            description = "Identifies one Crossref DOI or search entry. No Redis key or command is accepted.",
            required = true,
            content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = CrossrefCacheInvalidationRequest::class))],
        )
        @RequestBody request: CrossrefCacheInvalidationRequest,
    ): CrossrefCacheInvalidationResponse = service.invalidate(credential, request)
}
