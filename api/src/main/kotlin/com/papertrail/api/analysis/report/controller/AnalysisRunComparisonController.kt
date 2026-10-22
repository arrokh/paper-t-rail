package com.papertrail.api.analysis.report.controller

import com.papertrail.api.analysis.report.http.AnalysisRunComparisonResponse
import com.papertrail.api.analysis.report.service.AnalysisRunComparisonService
import com.papertrail.api.http.ApiError
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/v1/analysis-runs")
@Tag(name = "Analysis Runs", description = "Upload Source Documents and inspect immutable Analysis Runs.")
class AnalysisRunComparisonController(
    private val analysisRunComparisonService: AnalysisRunComparisonService,
) {
    @Operation(
        summary = "Compare two immutable Analysis Run reports",
        description = "Returns a read-only, versioned comparison of persisted Atomic Claim × Cited Reference results. When Source Document bytes and normalized parser text match, runs align only by unique source spans and compatible reference identity. If parser text differs but bytes match, alignment requires unique exact normalized claim text and a compatible identity, preferring source-local bibliography identity to preserve resolution changes. If source bytes differ, alignment requires unique exact normalized claim text and one consistent DOI or Canonical Paper identity. Ambiguous or identity-incompatible candidates remain separate and unmatched. Processing incompleteness is separate from final machine statuses. Human Reviews remain attached to their originating Verification and are excluded from machine-result alignment and change counts. No Analysis Run is modified, rerun, or sent to a provider.",
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "Read-only comparison of the two persisted reports", content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = AnalysisRunComparisonResponse::class))]),
            ApiResponse(responseCode = "400", description = "A run identifier is not a UUID", content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiError::class))]),
            ApiResponse(responseCode = "404", description = "One of the Analysis Runs was not found", content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiError::class))]),
            ApiResponse(responseCode = "409", description = "Parsed structure or report data is not available for one of the Analysis Runs", content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiError::class))]),
        ],
    )
    @GetMapping("/comparison", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun compare(
        @Parameter(description = "UUID of the left-hand Analysis Run.", required = true, schema = Schema(type = "string", format = "uuid"))
        @RequestParam leftRunId: UUID,
        @Parameter(description = "UUID of the right-hand Analysis Run.", required = true, schema = Schema(type = "string", format = "uuid"))
        @RequestParam rightRunId: UUID,
    ): AnalysisRunComparisonResponse = analysisRunComparisonService.compare(leftRunId, rightRunId)
}
