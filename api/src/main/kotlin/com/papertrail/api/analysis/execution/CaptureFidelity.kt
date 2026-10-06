package com.papertrail.api.analysis.execution

enum class CaptureFidelity {
    COMPLETE,
    SANITIZED,
    PARTIAL,
    OMITTED,
    REMOVED,
    UNAVAILABLE,
}
