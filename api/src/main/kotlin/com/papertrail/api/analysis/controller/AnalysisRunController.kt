package com.papertrail.api.analysis.controller

import com.fasterxml.jackson.databind.JsonNode
import com.papertrail.api.analysis.http.AnalysisRunPage
import com.papertrail.api.analysis.http.AnalysisRunSourcePdfAccess
import com.papertrail.api.analysis.http.AnalysisRunSummary
import com.papertrail.api.analysis.http.CreatedAnalysisRunResponse
import com.papertrail.api.analysis.http.RunConfigurationRequest
import com.papertrail.api.analysis.http.UploadAnalysisRunRequest
import com.papertrail.api.analysis.service.AnalysisRunService
import com.papertrail.api.citation.repository.ParsedDocumentView
import com.papertrail.api.document.validation.DocumentValidationException
import com.papertrail.api.http.ApiError
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.headers.Header
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import io.swagger.v3.oas.annotations.parameters.RequestBody as OpenApiRequestBody
import org.springframework.http.HttpStatus
import org.springframework.http.CacheControl
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Analysis Runs", description = "Upload Source Documents and inspect immutable Analysis Runs.")
class AnalysisRunController(
    private val analysisRunService: AnalysisRunService,
) {
    @Operation(
        summary = "Upload a PDF and create an Analysis Run",
        description = "Validates and stores an English text-based PDF, then creates a new immutable run and queues it. Sanitized execution capture defaults to enabled and may be opted out with captureExecution=false; captured artifacts are inspectable by anyone with access to this trusted local workspace because the API has no per-user ownership ACL. This disclosure is separate from external-provider consent. Each selected external provider requires explicit per-run consent for every declared data category and the exact server-issued disclosure fingerprint; disclosure text is snapshotted server-side and cannot be supplied by the client. The worker enforces the pinned claim-citation pair limit after parsing; over-limit runs fail with an explicit count and are not truncated or persisted as parsed structure.",
        requestBody = OpenApiRequestBody(
            required = true,
            content = [Content(
                mediaType = MediaType.MULTIPART_FORM_DATA_VALUE,
                schema = Schema(implementation = UploadAnalysisRunRequest::class),
            )],
        ),
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "201", description = "Source Document stored and Analysis Run queued", content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = CreatedAnalysisRunResponse::class))]),
            ApiResponse(responseCode = "400", description = "The upload or configuration is invalid", content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiError::class))]),
            ApiResponse(responseCode = "413", description = "Upload exceeds configured request-size limits", content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiError::class))]),
            ApiResponse(responseCode = "503", description = "Source Document storage is temporarily unavailable", content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiError::class))]),
        ],
    )
    @PostMapping("/analysis-runs", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE], produces = [MediaType.APPLICATION_JSON_VALUE])
    fun uploadAndStartAnalysis(
        @RequestPart("file") file: MultipartFile,
        @Parameter(hidden = true)
        @RequestParam(name = "configuration", required = false) configuration: String?,
    ): ResponseEntity<CreatedAnalysisRunResponse> {
        if (file.size == 0L) {
            throw DocumentValidationException("EMPTY_UPLOAD", "Choose a non-empty PDF file.")
        }
        val maxBytes = analysisRunService.maxUploadBytes()
        if (file.size > maxBytes) {
            throw DocumentValidationException(
                "UPLOAD_TOO_LARGE",
                "The PDF exceeds the configured $maxBytes-byte upload limit.",
            )
        }
        val bytes = file.bytes
        val requestedConfig = analysisRunService.parseConfigurationRequest(configuration)
        return ResponseEntity.status(HttpStatus.CREATED).body(
            analysisRunService.createFromUpload(file.originalFilename, file.contentType, bytes, requestedConfig),
        )
    }

    @Operation(
        summary = "Create a new run for a stored Source Document",
        description = "Sanitized execution capture defaults to enabled and may be opted out with captureExecution=false. Captured artifacts are inspectable by anyone with access to this trusted local workspace because the API has no per-user ownership ACL; this is separate from external-provider consent. Each selected external provider requires explicit per-run consent for every declared data category and the exact server-issued retention-disclosure fingerprint. Disclosure text is resolved and snapshotted server-side; clients cannot supply or change it.",
        requestBody = OpenApiRequestBody(
            required = false,
            content = [Content(
                mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = Schema(implementation = RunConfigurationRequest::class),
            )],
        ),
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "201", description = "New immutable Analysis Run queued", content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = CreatedAnalysisRunResponse::class))]),
            ApiResponse(responseCode = "400", description = "Analysis configuration is invalid", content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiError::class))]),
            ApiResponse(responseCode = "404", description = "Source Document not found", content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiError::class))]),
            ApiResponse(responseCode = "409", description = "Stored Source Document integrity check failed", content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiError::class))]),
            ApiResponse(responseCode = "503", description = "Stored Source Document is temporarily unavailable", content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiError::class))]),
        ],
    )
    @PostMapping("/documents/{documentId}/analysis-runs", consumes = [MediaType.APPLICATION_JSON_VALUE], produces = [MediaType.APPLICATION_JSON_VALUE])
    fun createReanalysis(
        @PathVariable documentId: UUID,
        @RequestBody(required = false) configuration: JsonNode?,
    ): ResponseEntity<CreatedAnalysisRunResponse> = ResponseEntity.status(HttpStatus.CREATED).body(
        analysisRunService.createReanalysis(documentId, configuration),
    )

    @Operation(
        summary = "List recent Analysis Runs",
        description = "Returns a cursor-paginated page in createdAt descending, then ID descending order. Pass nextCursor or previousCursor as cursor to move through the list. Filename and status filters are applied before pagination.",
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "Cursor-paginated Analysis Runs", content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = AnalysisRunPage::class))]),
            ApiResponse(responseCode = "400", description = "The pagination cursor, filename query, or status filter is invalid", content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiError::class))]),
        ],
    )
    @GetMapping("/analysis-runs", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun list(
        @Parameter(description = "Maximum runs to return; values are clamped to 1–100.")
        @RequestParam(defaultValue = "25") limit: Int,
        @Parameter(description = "Opaque cursor from the previous page; omit to list the newest runs.")
        @RequestParam(required = false) cursor: String?,
        @Parameter(description = "Optional case-insensitive filename search, up to 200 characters.")
        @RequestParam(required = false) q: String? = null,
        @Parameter(description = "Optional exact Analysis Run status filter.")
        @RequestParam(required = false) status: String? = null,
    ): AnalysisRunPage = analysisRunService.list(limit, cursor, q, status)

    @Operation(summary = "Get an Analysis Run and its persisted progress")
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "Persisted Analysis Run and progress", content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = AnalysisRunSummary::class))]),
            ApiResponse(responseCode = "404", description = "Analysis Run not found", content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiError::class))]),
        ],
    )
    @GetMapping("/analysis-runs/{runId}", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun get(@PathVariable runId: UUID): AnalysisRunSummary = analysisRunService.get(runId)
        ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Analysis Run not found.")

    @Operation(
        summary = "Get short-lived original Source Document PDF URLs for an Analysis Run",
        description = "Returns short-lived, read-only S3-compatible presigned URLs for viewing and downloading the exact uploaded PDF pinned to this Analysis Run, including while worker processing is in progress. The URL is valid for six hours and must be treated as a bearer credential.",
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "Short-lived view and download URLs",
                content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = AnalysisRunSourcePdfAccess::class))],
                headers = [Header(name = "Cache-Control", description = "Always no-store because the response contains bearer URLs.", schema = Schema(type = "string"))],
            ),
            ApiResponse(responseCode = "404", description = "Analysis Run not found or its Source Document was deleted", content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiError::class))]),
            ApiResponse(responseCode = "409", description = "Stored Source Document integrity check failed", content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiError::class))]),
            ApiResponse(responseCode = "503", description = "Stored Source Document is temporarily unavailable", content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiError::class))]),
        ],
    )
    @GetMapping("/analysis-runs/{runId}/source-document", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun getSourceDocumentAccess(
        @Parameter(description = "Analysis Run identifier.", required = true, schema = Schema(type = "string", format = "uuid"))
        @PathVariable runId: UUID,
    ): ResponseEntity<AnalysisRunSourcePdfAccess> {
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .body(analysisRunService.getSourcePdfAccess(runId))
    }

    @Operation(
        summary = "Get parsed document structure, Atomic Claims, resolution status, and inferred links",
        description = "Returns the immutable parsed structure, extracted Atomic Claims, inferred/provisional all-to-all Claim–Citation Target links scoped to each Citation Context, and current bibliography resolution status projected from separate immutable outcomes when an Analysis Run reaches PARSED. PARSED means final Claim–Paper Verification is not complete; a selected non-mock System One provider may have recorded uncalibrated passage judgements without aggregation. All source offsets are zero-based and end-exclusive UTF-16 code-unit indexes in normalizedSourceText.",
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "Persisted parsed document structure", content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ParsedDocumentView::class))]),
            ApiResponse(responseCode = "404", description = "Analysis Run not found", content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiError::class))]),
            ApiResponse(responseCode = "409", description = "Parsed document structure is not ready", content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiError::class))]),
        ],
    )
    @GetMapping("/analysis-runs/{runId}/parsed-document", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun getParsedDocument(@PathVariable runId: UUID): ParsedDocumentView = analysisRunService.getParsedDocument(runId)
}
