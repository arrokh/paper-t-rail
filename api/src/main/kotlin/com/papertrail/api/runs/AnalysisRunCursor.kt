package com.papertrail.api.runs

import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.Base64
import java.util.UUID

internal data class AnalysisRunCursor(
    val createdAt: Instant,
    val id: UUID,
)

internal object AnalysisRunCursorCodec {
    private const val MAX_CURSOR_LENGTH = 256
    private const val SEPARATOR = '|'

    fun encode(createdAt: Instant, id: UUID): String {
        val payload = "$createdAt$SEPARATOR$id".toByteArray(StandardCharsets.UTF_8)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(payload)
    }

    fun decode(value: String): AnalysisRunCursor {
        if (value.isBlank() || value.length > MAX_CURSOR_LENGTH) invalidCursor()

        val payload = try {
            String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8)
        } catch (_: IllegalArgumentException) {
            invalidCursor()
        }
        val separatorIndex = payload.indexOf(SEPARATOR)
        if (separatorIndex <= 0 || payload.indexOf(SEPARATOR, separatorIndex + 1) >= 0) invalidCursor()

        val createdAt = try {
            Instant.parse(payload.substring(0, separatorIndex))
        } catch (_: RuntimeException) {
            invalidCursor()
        }
        val idText = payload.substring(separatorIndex + 1)
        val id = try {
            UUID.fromString(idText)
        } catch (_: IllegalArgumentException) {
            invalidCursor()
        }
        if (id.toString() != idText) invalidCursor()

        return AnalysisRunCursor(createdAt, id)
    }

    private fun invalidCursor(): Nothing = throw IllegalArgumentException("Analysis Run cursor is invalid.")
}
