package com.papertrail.api.analysis.execution.pagination

import com.papertrail.api.infrastructure.pagination.OpaqueCursorTokenCodec
import java.time.Instant
import java.util.UUID

internal data class ExecutionSpanCursor(val startedAt: Instant, val id: UUID)

internal object ExecutionSpanCursorCodec {
    private const val MAX_CURSOR_LENGTH = 256
    private const val SEPARATOR = '|'

    fun encode(startedAt: Instant, id: UUID): String = OpaqueCursorTokenCodec.encode("$startedAt$SEPARATOR$id")

    fun decode(value: String): ExecutionSpanCursor {
        val decoded = OpaqueCursorTokenCodec.decode(value, MAX_CURSOR_LENGTH, INVALID_CURSOR_MESSAGE)
        val fields = decoded.split(SEPARATOR)
        if (fields.size != 2) invalidCursor()
        val timestamp = try {
            Instant.parse(fields[0])
        } catch (_: RuntimeException) {
            invalidCursor()
        }
        val id = try {
            UUID.fromString(fields[1])
        } catch (_: IllegalArgumentException) {
            invalidCursor()
        }
        if (id.toString() != fields[1]) invalidCursor()
        return ExecutionSpanCursor(timestamp, id)
    }

    private fun invalidCursor(): Nothing = throw IllegalArgumentException(INVALID_CURSOR_MESSAGE)

    private const val INVALID_CURSOR_MESSAGE = "Execution span cursor is invalid."
}
