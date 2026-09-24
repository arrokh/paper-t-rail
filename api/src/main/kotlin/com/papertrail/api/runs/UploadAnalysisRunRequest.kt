package com.papertrail.api.runs

import io.swagger.v3.oas.annotations.media.Schema
import org.springframework.web.multipart.MultipartFile

@Schema(name = "UploadAnalysisRunRequest", description = "Multipart form used to create an Analysis Run from a PDF.")
data class UploadAnalysisRunRequest(
    @field:Schema(type = "string", format = "binary", description = "English text-based PDF to validate and store.")
    val file: MultipartFile,
    @field:Schema(
        type = "string",
        description = "JSON-encoded local provider configuration.",
    )
    val configuration: String? = null,
)
