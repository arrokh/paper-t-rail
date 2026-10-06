package com.papertrail.api.infrastructure.pagination

import java.nio.charset.StandardCharsets
import java.util.Base64

/** Shared opaque Base64URL token encoding; feature codecs own their payload semantics. */
internal object OpaqueCursorTokenCodec {
    fun encode(payload: String): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(payload.toByteArray(StandardCharsets.UTF_8))

    fun decode(value: String, maximumLength: Int, invalidCursorMessage: String): String {
        if (value.isBlank() || value.length > maximumLength) invalidCursor(invalidCursorMessage)
        return try {
            String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8)
        } catch (_: IllegalArgumentException) {
            invalidCursor(invalidCursorMessage)
        }
    }

    private fun invalidCursor(message: String): Nothing = throw IllegalArgumentException(message)
}
