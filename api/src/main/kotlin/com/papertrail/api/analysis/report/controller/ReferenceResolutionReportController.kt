package com.papertrail.api.analysis.report.controller

import com.papertrail.api.analysis.report.http.ReferenceResolutionReportResponse
import com.papertrail.api.analysis.report.service.AnalysisRunReportService
import com.papertrail.api.http.ApiError
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
    private val analysisRunReportService: AnalysisRunReportService,
) {
    @Operation(
        summary = "Get the Reference Resolution Report",
        description = "Returns persisted conservative bibliography-resolution results, Canonical Paper identities, configured matching policy, and per-run threshold, plus Claim–Reference outcomes and ranked Evidence Passages. An over-limit passage selected for local Laya evaluation may include ordered sentence-span diagnostics with exact offsets, context overlap, token counts, provenance, and incomplete reasons. Span judgements remain under their immutable parent passage and are never rolled up into a parent Evidence Judgement or final Claim–Paper status; processing-incomplete pairs have no final domain status.",
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "Persisted bibliography resolution report", content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ReferenceResolutionReportResponse::class))]),
            ApiResponse(responseCode = "404", description = "Analysis Run not found", content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiError::class))]),
        ],
    )
    @GetMapping("/analysis-runs/{runId}/report", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun getReport(@PathVariable runId: UUID): ReferenceResolutionReportResponse = analysisRunReportService.report(runId)
        ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Analysis Run not found.")
}
