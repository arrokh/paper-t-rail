package com.papertrail.api.analysis.pagination

import com.papertrail.api.infrastructure.pagination.OpaqueCursorTokenCodec
import java.time.Instant
import java.util.UUID

internal data class AnalysisRunCursor(
    val createdAt: Instant,
    val id: UUID,
    val direction: Direction = Direction.NEXT,
)

internal enum class Direction { NEXT, PREVIOUS }

internal object AnalysisRunCursorCodec {
    private const val MAX_CURSOR_LENGTH = 256
    private const val SEPARATOR = '|'

    fun encode(createdAt: Instant, id: UUID, direction: Direction): String {
        val payload = "$direction$SEPARATOR$createdAt$SEPARATOR$id"
        return OpaqueCursorTokenCodec.encode(payload)
    }

    fun encode(createdAt: Instant, id: UUID): String = encode(createdAt, id, Direction.NEXT)

    fun decode(value: String): AnalysisRunCursor {
        val payload = OpaqueCursorTokenCodec.decode(value, MAX_CURSOR_LENGTH, INVALID_CURSOR_MESSAGE)
        val fields = payload.split(SEPARATOR)
        val (direction, timestampText, idText) = when (fields.size) {
            // Keep cursors issued by the older one-way API usable as next-page cursors.
            2 -> Triple(Direction.NEXT, fields[0], fields[1])
            3 -> Triple(
                runCatching { Direction.valueOf(fields[0]) }.getOrElse { invalidCursor() },
                fields[1],
                fields[2],
            )
            else -> invalidCursor()
        }
        val createdAt = try {
            Instant.parse(timestampText)
        } catch (_: RuntimeException) {
            invalidCursor()
        }
        val id = try {
            UUID.fromString(idText)
        } catch (_: IllegalArgumentException) {
            invalidCursor()
        }
        if (id.toString() != idText) invalidCursor()

        return AnalysisRunCursor(createdAt, id, direction)
    }

    private fun invalidCursor(): Nothing = throw IllegalArgumentException(INVALID_CURSOR_MESSAGE)

    private const val INVALID_CURSOR_MESSAGE = "Analysis Run cursor is invalid."
}
