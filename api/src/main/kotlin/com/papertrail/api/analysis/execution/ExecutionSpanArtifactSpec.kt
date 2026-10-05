package com.papertrail.api.analysis.execution

data class ExecutionSpanArtifactSpec(
    val role: String,
    val schemaVersion: String,
    val fields: Map<String, Any?>,
)
