package com.papertrail.api.analysis.execution.domain

data class SanitizedExecutionArtifact(
    val content: String?,
    val fidelity: CaptureFidelity,
    val reason: String?,
    val schemaVersion: String,
    val mediaType: String = "application/json",
)
