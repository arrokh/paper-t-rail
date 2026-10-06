package com.papertrail.api.analysis.execution.pagination

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class ExecutionSpanCursorCodecTest {
    @Test
    fun `encoded cursor preserves its timestamp and span ID`() {
        val timestamp = Instant.parse("2025-01-02T03:04:05.123456789Z")
        val spanId = UUID.fromString("923e4567-e89b-12d3-a456-426614174000")

        assertEquals(ExecutionSpanCursor(timestamp, spanId), ExecutionSpanCursorCodec.decode(ExecutionSpanCursorCodec.encode(timestamp, spanId)))
    }

    @Test
    fun `invalid cursors are rejected without echoing their input`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            ExecutionSpanCursorCodec.decode("invalid-cursor")
        }

        assertEquals("Execution span cursor is invalid.", error.message)
    }
}
