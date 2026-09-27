package com.papertrail.api.scholarly.acquisition.controller

import com.papertrail.api.http.ApiError
import com.papertrail.api.infrastructure.cache.CacheInvalidationResponse
import com.papertrail.api.scholarly.acquisition.http.UnpaywallCacheInvalidationRequest
import com.papertrail.api.scholarly.acquisition.service.UnpaywallCacheInvalidationService
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
@RequestMapping("/api/v1/operator/caches/unpaywall")
@Tag(name = "Operator", description = "Credential-protected operational controls unavailable through the web proxy.")
class UnpaywallCacheInvalidationController(
    private val service: UnpaywallCacheInvalidationService,
) {
    @Operation(
        summary = "Invalidate one Unpaywall DOI cache entry",
        description = "Deletes exactly one normalized Unpaywall DOI discovery entry. Requires the server-side operator credential; arbitrary Redis commands and provider-wide invalidation are not supported.",
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "The selected DOI entry was deleted, or did not exist.", content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = CacheInvalidationResponse::class))]),
            ApiResponse(responseCode = "400", description = "The request does not identify one valid DOI.", content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiError::class))]),
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
            description = "Identifies one Unpaywall DOI entry. No Redis key or command is accepted.",
            required = true,
            content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = UnpaywallCacheInvalidationRequest::class))],
        )
        @RequestBody request: UnpaywallCacheInvalidationRequest,
    ): CacheInvalidationResponse = service.invalidate(credential, request)
}
