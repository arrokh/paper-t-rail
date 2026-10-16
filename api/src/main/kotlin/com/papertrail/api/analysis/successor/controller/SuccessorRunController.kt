package com.papertrail.api.analysis.successor.controller

import com.papertrail.api.analysis.successor.http.SuccessorRunPreviewRequest
import com.papertrail.api.analysis.successor.http.SuccessorRunPreviewResponse
import com.papertrail.api.analysis.successor.http.SuccessorRunSubmissionRequest
import com.papertrail.api.analysis.successor.http.SuccessorRunSubmissionResponse
import com.papertrail.api.analysis.successor.http.toResponse
import com.papertrail.api.analysis.successor.service.SuccessorRunService
import com.papertrail.api.analysis.successor.service.SuccessorRunSubmissionService
import com.papertrail.api.analysis.recovery.service.RecoverySubmissionGate
import com.papertrail.api.http.ApiError
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.parameters.RequestBody
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.CacheControl
import org.springframework.http.MediaType
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody as SpringRequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Successor Runs", description = "Preview and submit selective Analysis Run successors without modifying their immutable predecessors.")
class SuccessorRunController(
    private val successorRunService: SuccessorRunService,
    private val successorRunSubmissionService: SuccessorRunSubmissionService,
    private val recoverySubmissionGate: RecoverySubmissionGate,
) {
    @Operation(
        summary = "Preview a Recovery Batch successor",
        description = "Builds a read-only plan from the Recovery Batch's current server-recorded selections and the supplied run configuration. Preview performs no writes, storage reads, provider calls, or cache reads; submit must recompute the plan under locks.",
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "Advisory successor plan, including stage invalidations, retained coverage, provider work, and consent snapshots", content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = SuccessorRunPreviewResponse::class))]),
            ApiResponse(responseCode = "400", description = "Invalid configuration or missing/mismatched external-provider consent", content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiError::class))]),
            ApiResponse(responseCode = "404", description = "Recovery Batch or predecessor Analysis Run not found, or its Source Document was deleted", content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiError::class))]),
            ApiResponse(responseCode = "409", description = "The expected Recovery Batch revision is stale", content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiError::class))]),
            ApiResponse(responseCode = "410", description = "Recovery Batch expired", content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiError::class))]),
        ],
    )
    @PostMapping(
        "/recovery-batches/{batchId}/successor/preview",
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    fun preview(
        @Parameter(description = "Recovery Batch identifier.", required = true)
        @PathVariable batchId: UUID,
        @RequestBody(
            description = "Expected batch revision and complete successor run configuration.",
            required = true,
            content = [Content(schema = Schema(implementation = SuccessorRunPreviewRequest::class))],
        )
        @Valid @SpringRequestBody request: SuccessorRunPreviewRequest,
    ): ResponseEntity<SuccessorRunPreviewResponse> = ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(successorRunService.preview(batchId, request).toResponse())

    @Operation(
        summary = "Submit a Recovery Batch successor",
        description = "Atomically materializes one immutable successor from the batch's current server-recorded selections. Submission verifies selected asset bytes outside the database transaction, then locks and rechecks the source, predecessor, batch revision, and exact selections before committing same-run results, provenance, durable asset ownership, and selected-only work. Retrying the same idempotency key and request returns the same successor.",
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "201", description = "Successor created and selected work queued", content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = SuccessorRunSubmissionResponse::class))]),
            ApiResponse(responseCode = "200", description = "Idempotent replay of the existing successor submission", content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = SuccessorRunSubmissionResponse::class))]),
            ApiResponse(responseCode = "400", description = "Invalid request or configuration", content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiError::class))]),
            ApiResponse(responseCode = "404", description = "Recovery Batch, predecessor, or active Source Document not found", content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiError::class))]),
            ApiResponse(responseCode = "409", description = "Stale revision, blocked plan, conflicting idempotency key, changed selection, or asset hash mismatch", content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiError::class))]),
            ApiResponse(responseCode = "410", description = "Recovery Batch expired or is not open for submission", content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiError::class))]),
            ApiResponse(responseCode = "503", description = "Recovery submissions are disabled (RECOVERY_SUBMISSIONS_DISABLED), or the selected finalized asset could not be verified", content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiError::class))]),
        ],
    )
    @PostMapping(
        "/recovery-batches/{batchId}/successor",
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    fun submit(
        @Parameter(description = "Recovery Batch identifier.", required = true)
        @PathVariable batchId: UUID,
        @RequestBody(
            description = "Idempotency key, expected batch revision, and complete successor run configuration.",
            required = true,
            content = [Content(schema = Schema(implementation = SuccessorRunSubmissionRequest::class))],
        )
        @Valid @SpringRequestBody request: SuccessorRunSubmissionRequest,
    ): ResponseEntity<SuccessorRunSubmissionResponse> {
        recoverySubmissionGate.requireEnabled()
        val result = successorRunSubmissionService.submit(batchId, request)
        return ResponseEntity.status(if (result.replayed) HttpStatus.OK else HttpStatus.CREATED)
            .cacheControl(CacheControl.noStore())
            .body(result.toResponse())
    }
}
