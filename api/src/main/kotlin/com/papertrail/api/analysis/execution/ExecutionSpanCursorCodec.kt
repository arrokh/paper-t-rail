package com.papertrail.api.analysis.execution

import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.Base64
import java.util.UUID

internal data class ExecutionSpanCursor(val startedAt: Instant, val id: UUID)

internal object ExecutionSpanCursorCodec {
    private const val MAX_CURSOR_LENGTH = 256
    private const val SEPARATOR = '|'

    fun encode(startedAt: Instant, id: UUID): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString("$startedAt$SEPARATOR$id".toByteArray(StandardCharsets.UTF_8))

    fun decode(value: String): ExecutionSpanCursor {
        if (value.isBlank() || value.length > MAX_CURSOR_LENGTH) invalidCursor()
        val decoded = try {
            String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8)
        } catch (_: IllegalArgumentException) {
            invalidCursor()
        }
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

    private fun invalidCursor(): Nothing = throw IllegalArgumentException("Execution span cursor is invalid.")
}
