package com.papertrail.api.analysis.recovery.http

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank

@Schema(description = "Explicit confirmation that a normalized DOI identifies the unresolved Bibliography Entry; this is a user assertion and does not verify a PDF version.")
data class RecoveryIdentifierAssistanceRequest(
    @field:NotBlank
    @field:Schema(description = "Identifier type supported by this recovery slice.", allowableValues = ["DOI"])
    val identifierType: String,
    @field:NotBlank
    @field:Schema(description = "DOI or DOI URL; the response contains its normalized DOI value.")
    val identifierValue: String,
    @field:Schema(description = "Must be true to stage the DOI as the researcher's identity assertion.")
    val confirmsBibliographyIdentity: Boolean,
)
