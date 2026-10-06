package com.papertrail.api.analysis.execution.domain

import java.nio.charset.StandardCharsets
import java.util.UUID

object ExecutionOperationId {
    fun forEvent(eventId: UUID, operationKey: String): UUID = UUID.nameUUIDFromBytes(
        "${eventId}:$operationKey".toByteArray(StandardCharsets.UTF_8),
    )
}
