package com.papertrail.api.scholarly.references.controller

import com.papertrail.api.http.ApiError
import com.papertrail.api.scholarly.references.report.ReferenceResolutionReportResponse
import com.papertrail.api.scholarly.references.service.ReferenceResolutionService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Analysis Runs", description = "Upload Source Documents and inspect immutable Analysis Runs.")
class ReferenceResolutionReportController(
    private val referenceResolutionService: ReferenceResolutionService,
) {
    @Operation(
        summary = "Get the Reference Resolution Report",
        description = "Returns persisted conservative bibliography-resolution results, Canonical Paper identities, configured matching policy, and per-run threshold. Claim and evidence analysis is not implied by a parsed run.",
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "Persisted bibliography resolution report", content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ReferenceResolutionReportResponse::class))]),
            ApiResponse(responseCode = "404", description = "Analysis Run not found", content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiError::class))]),
        ],
    )
    @GetMapping("/analysis-runs/{runId}/report", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun getReport(@PathVariable runId: UUID): ReferenceResolutionReportResponse = referenceResolutionService.report(runId)
        ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Analysis Run not found.")
}
