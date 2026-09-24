package com.papertrail.api.runs

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.papertrail.api.documents.ApiError
import com.papertrail.api.parsing.ParsedDocumentRepository
import com.papertrail.api.parsing.ParsedDocumentView
import com.papertrail.api.providers.ProviderCatalog
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.JdbcTemplate
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
    private val configurationFactory: RunConfigurationFactory,
    private val objectMapper: ObjectMapper,
    private val jdbc: JdbcTemplate,
    private val parsedDocumentRepository: ParsedDocumentRepository,
    private val providerCatalog: ProviderCatalog,
) {
    @Operation(
        summary = "Upload a PDF and create an Analysis Run",
        description = "Validates and stores an English text-based PDF, then creates a new immutable run and queues it.",
        requestBody = io.swagger.v3.oas.annotations.parameters.RequestBody(
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
            throw com.papertrail.api.documents.DocumentValidationException("EMPTY_UPLOAD", "Choose a non-empty PDF file.")
        }
        val maxBytes = analysisRunService.maxUploadBytes()
        if (file.size > maxBytes) {
            throw com.papertrail.api.documents.DocumentValidationException(
                "UPLOAD_TOO_LARGE",
                "The PDF exceeds the configured $maxBytes-byte upload limit.",
            )
        }
        val bytes = file.bytes
        val requestedConfig = configurationFactory.parseRequest(parseConfiguration(configuration))
        return ResponseEntity.status(HttpStatus.CREATED).body(
            analysisRunService.createFromUpload(file.originalFilename, file.contentType, bytes, requestedConfig),
        )
    }

    @Operation(
        summary = "Create a new run for a stored Source Document",
        requestBody = io.swagger.v3.oas.annotations.parameters.RequestBody(
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

    @Operation(summary = "List providers available for Analysis Run selection")
    @GetMapping("/providers", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun providers() = providerCatalog.directory()

    @Operation(summary = "List recent Analysis Runs")
    @GetMapping("/analysis-runs", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun list(
        @Parameter(description = "Maximum runs to return; values are clamped to 1–100.")
        @RequestParam(defaultValue = "25") limit: Int,
    ): List<AnalysisRunSummary> = analysisRunService.list(limit)

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
        summary = "Get parsed sections, citation contexts, citation occurrences, and bibliography entries",
        description = "Returns the immutable parsed structure and parser provenance when an Analysis Run reaches PARSED. PARSED is an intermediate state, not a completed Evidence Coverage Report. All source offsets are zero-based and end-exclusive UTF-16 code-unit indexes in normalizedSourceText.",
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "Persisted parsed document structure", content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ParsedDocumentView::class))]),
            ApiResponse(responseCode = "404", description = "Analysis Run not found", content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiError::class))]),
            ApiResponse(responseCode = "409", description = "Parsed document structure is not ready", content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiError::class))]),
        ],
    )
    @GetMapping("/analysis-runs/{runId}/parsed-document", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun getParsedDocument(@PathVariable runId: UUID): ParsedDocumentView {
        if (analysisRunService.get(runId) == null) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Analysis Run not found.")
        }
        return parsedDocumentRepository.find(runId)
            ?: throw ResponseStatusException(HttpStatus.CONFLICT, "Parsed document structure is not available until parsing completes.")
    }

    @Operation(summary = "Check API and database liveness")
    @GetMapping("/health", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun health(): Map<String, String> {
        jdbc.queryForObject("SELECT 1", Int::class.java)
        return mapOf("status" to "ok")
    }

    private fun parseConfiguration(configuration: String?): JsonNode? {
        if (configuration.isNullOrBlank()) return null
        return try {
            objectMapper.readTree(configuration)
        } catch (exception: Exception) {
            throw IllegalArgumentException("Analysis configuration must be valid JSON.")
        }
    }
}
