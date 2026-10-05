package com.papertrail.api.infrastructure.messaging

/** A pipeline failure whose cause cannot be repaired by redelivering the same immutable work. */
open class NonRetryablePipelineException(message: String) : IllegalArgumentException(message)
