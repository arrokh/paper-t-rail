package com.papertrail.api.infrastructure.messaging

import java.time.Instant

/** Outcome after a queue handler durably arbitrates a terminal processing failure. */
sealed interface PipelineHandlerFailureDisposition {
    data object FailureRecorded : PipelineHandlerFailureDisposition

    /** A successful final result already exists; acknowledge the event without sending it to the DLQ. */
    data object AlreadyCompleted : PipelineHandlerFailureDisposition

    /** A fenced owner or recoverable reservation still owns this work. */
    data class Defer(val until: Instant) : PipelineHandlerFailureDisposition
}
