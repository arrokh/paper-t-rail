package com.papertrail.api.analysis.execution

import com.papertrail.api.http.ApiError
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.headers.Header
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.CacheControl
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/v1/analysis-runs/{runId}/execution")
@Tag(name = "Analysis Run Execution", description = "Inspect content-free Analysis Run spans and separately captured, sanitized artifacts inside the trusted local workspace.")
class AnalysisRunExecutionController(
    private val executionService: AnalysisRunExecutionService,
) {
    @Operation(
        summary = "Get Analysis Run execution availability and elapsed interval",
        description = "Legacy runs return NOT_RECORDED with null timing. Capture artifacts are limited to the same trusted-workspace boundary as existing run inspection; this unauthenticated local API does not provide per-user ownership checks.",
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "Execution summary", content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = AnalysisRunExecutionSummary::class))]),
            ApiResponse(responseCode = "404", description = "Analysis Run not found or its Source Document was deleted", content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiError::class))]),
        ],
    )
    @GetMapping(produces = [MediaType.APPLICATION_JSON_VALUE])
    fun summary(@PathVariable runId: UUID): AnalysisRunExecutionSummary = executionService.summary(runId)

    @Operation(
        summary = "List timed execution spans",
        description = "Returns spans sorted by startedAt then ID ascending, with a bounded opaque forward cursor. The default page size is 100 and values are clamped to 1–100; payload artifacts are never loaded in this list response.",
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "Cursor page of content-free spans", content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ExecutionSpanPage::class))]),
            ApiResponse(responseCode = "400", description = "Invalid opaque span cursor", content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiError::class))]),
            ApiResponse(responseCode = "404", description = "Analysis Run not found", content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiError::class))]),
        ],
    )
    @GetMapping("/spans", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun spans(
        @PathVariable runId: UUID,
        @Parameter(description = "Requested page size; values are clamped to 1–100.")
        @RequestParam(defaultValue = "100") limit: Int,
        @Parameter(description = "Opaque cursor from the previous span page; order is startedAt then ID ascending.")
        @RequestParam(required = false) cursor: String?,
    ): ExecutionSpanPage = executionService.spans(runId, limit, cursor)

    @Operation(
        summary = "Get one execution span and its artifact descriptors",
        description = "Returns safe metadata for one span. Artifact content is fetched separately by artifact ID only after explicit selection.",
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "Span detail and artifact descriptors", content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ExecutionSpanResponse::class))]),
            ApiResponse(responseCode = "404", description = "Analysis Run or span not found", content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiError::class))]),
        ],
    )
    @GetMapping("/spans/{spanId}", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun span(@PathVariable runId: UUID, @PathVariable spanId: UUID): ExecutionSpanResponse = executionService.span(runId, spanId)

    @Operation(
        summary = "Get one sanitized execution artifact",
        description = "Returns only a separately stored sanitized JSON artifact linked to this run. Optional spanId and role parameters select one specific run-local association; if supplied, that exact association must exist. Removed content has a REMOVED fidelity and no body. Response caching is disabled. Anyone with access to this trusted local workspace can inspect artifacts; no user or owner identity is asserted.",
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "Sanitized artifact or truthful removed state",
                content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ExecutionArtifactResponse::class))],
                headers = [Header(name = "Cache-Control", description = "Always no-store for protected execution content.", schema = Schema(type = "string"))],
            ),
            ApiResponse(responseCode = "400", description = "Unsupported artifact role", content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiError::class))]),
            ApiResponse(responseCode = "404", description = "Analysis Run, artifact, or requested span/role association not found in this run", content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiError::class))]),
        ],
    )
    @GetMapping("/artifacts/{artifactId}", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun artifact(
        @PathVariable runId: UUID,
        @PathVariable artifactId: UUID,
        @Parameter(description = "Optional execution span association; must belong to this Analysis Run and artifact.")
        @RequestParam(required = false) spanId: UUID?,
        @Parameter(description = "Optional artifact role association: INPUT, REQUEST, RESPONSE, or RESULT.", schema = Schema(allowableValues = ["INPUT", "REQUEST", "RESPONSE", "RESULT"]))
        @RequestParam(required = false) role: String?,
    ): ResponseEntity<ExecutionArtifactResponse> =
        ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(executionService.artifact(runId, artifactId, spanId, role))

    @Operation(
        summary = "Stop prospective execution capture",
        description = "Disables all future execution span and artifact writes for this run. Existing artifacts remain until separately removed, and no historical backfill is performed. This operation does not change the original capture choice or analysis results.",
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "Prospective capture stopped", content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = AnalysisRunExecutionSummary::class))]),
            ApiResponse(responseCode = "404", description = "Analysis Run not found", content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiError::class))]),
            ApiResponse(responseCode = "409", description = "Legacy run has no execution recording to stop", content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiError::class))]),
        ],
    )
    @PatchMapping("/capture", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun stopCapture(@PathVariable runId: UUID): AnalysisRunExecutionSummary = executionService.stopCapture(runId)

    @Operation(
        summary = "Remove one sanitized execution artifact",
        description = "Permanently clears artifact body content from its run-local deduplicated artifact and marks every affected span association REMOVED. Analysis results and span timing are unchanged.",
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "204", description = "Artifact content removed"),
            ApiResponse(responseCode = "404", description = "Analysis Run or artifact not found in this run", content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiError::class))]),
        ],
    )
    @DeleteMapping("/artifacts/{artifactId}")
    fun removeArtifact(@PathVariable runId: UUID, @PathVariable artifactId: UUID): ResponseEntity<Void> {
        executionService.removeArtifact(runId, artifactId)
        return ResponseEntity.noContent().build()
    }
}
