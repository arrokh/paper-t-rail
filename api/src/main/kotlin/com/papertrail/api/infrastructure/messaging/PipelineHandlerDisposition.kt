package com.papertrail.api.infrastructure.messaging

import java.time.Instant

/** A queue handler's durable processing disposition. */
sealed interface PipelineHandlerDisposition {
    data object Done : PipelineHandlerDisposition

    /** Keep the event pending and schedule another delivery no earlier than [until]. */
    data class Defer(val until: Instant) : PipelineHandlerDisposition

    /** Persist failure through the handler callback, then move the event to the dead-letter stream. */
    data class TerminalFailure(val code: String) : PipelineHandlerDisposition {
        init {
            require(code.matches(Regex("[A-Z][A-Z0-9_]{0,63}"))) {
                "Terminal pipeline failure code must be a fixed safe identifier."
            }
        }
    }
}
