package com.papertrail.api.analysis.execution.http

import io.swagger.v3.oas.annotations.media.Schema
import java.util.UUID

@Schema(description = "A link to an existing domain record associated with this execution span.")
data class ExecutionDomainLink(
    @field:Schema(allowableValues = ["ANALYSIS_RUN", "SOURCE_DOCUMENT", "BIBLIOGRAPHY_ENTRY"])
    val type: String,
    val id: UUID,
    @field:Schema(description = "Existing same-origin API route for the linked record or report.")
    val href: String,
)
