package com.papertrail.api.runs

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
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
class AnalysisRunController(
    private val analysisRunService: AnalysisRunService,
    private val configurationFactory: RunConfigurationFactory,
    private val objectMapper: ObjectMapper,
    private val jdbc: JdbcTemplate,
) {
    @PostMapping("/analysis-runs", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun uploadAndStartAnalysis(
        @RequestPart("file") file: MultipartFile,
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

    @PostMapping("/documents/{documentId}/analysis-runs", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun createReanalysis(
        @PathVariable documentId: UUID,
        @RequestBody(required = false) configuration: JsonNode?,
    ): ResponseEntity<CreatedAnalysisRunResponse> = ResponseEntity.status(HttpStatus.CREATED).body(
        analysisRunService.createReanalysis(documentId, configuration),
    )

    @GetMapping("/analysis-runs")
    fun list(@RequestParam(defaultValue = "25") limit: Int): List<AnalysisRunSummary> = analysisRunService.list(limit)

    @GetMapping("/analysis-runs/{runId}")
    fun get(@PathVariable runId: UUID): AnalysisRunSummary = analysisRunService.get(runId)
        ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Analysis Run not found.")

    @GetMapping("/health")
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
