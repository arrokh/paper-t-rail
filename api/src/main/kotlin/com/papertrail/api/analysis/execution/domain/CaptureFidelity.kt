package com.papertrail.api.analysis.execution.domain

enum class CaptureFidelity {
    COMPLETE,
    SANITIZED,
    PARTIAL,
    OMITTED,
    REMOVED,
    UNAVAILABLE,
}
