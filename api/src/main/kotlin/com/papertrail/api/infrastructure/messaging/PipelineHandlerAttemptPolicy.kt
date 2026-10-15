package com.papertrail.api.infrastructure.messaging

/** Defines when a queue delivery consumes a handler attempt. */
enum class PipelineHandlerAttemptPolicy {
    /** Every Redis delivery consumes an attempt, including deliveries that the handler defers. */
    REDIS_DELIVERY,

    /** Only a completed handler disposition consumes an attempt; deferred ownership checks do not. */
    HANDLER_DISPOSITION,
}
