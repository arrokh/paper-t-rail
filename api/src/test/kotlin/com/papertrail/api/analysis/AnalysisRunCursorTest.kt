package com.papertrail.api.analysis

import com.papertrail.api.analysis.pagination.AnalysisRunCursor
import com.papertrail.api.analysis.pagination.AnalysisRunCursorCodec
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.Base64
import java.util.UUID

class AnalysisRunCursorTest {
    @Test
    fun `encoded cursor preserves its exact timestamp and run ID`() {
        val timestamp = Instant.parse("2025-01-02T03:04:05.123456789Z")
        val runId = UUID.fromString("923e4567-e89b-12d3-a456-426614174000")

        val decoded = AnalysisRunCursorCodec.decode(AnalysisRunCursorCodec.encode(timestamp, runId))

        assertEquals(AnalysisRunCursor(timestamp, runId), decoded)
    }

    @Test
    fun `legacy two-field cursors decode as next-page cursors`() {
        val timestamp = Instant.parse("2025-01-02T03:04:05.123456789Z")
        val runId = UUID.fromString("923e4567-e89b-12d3-a456-426614174000")
        val legacyPayload = "$timestamp|$runId".toByteArray(StandardCharsets.UTF_8)
        val legacyCursor = Base64.getUrlEncoder().withoutPadding().encodeToString(legacyPayload)

        assertEquals(AnalysisRunCursor(timestamp, runId), AnalysisRunCursorCodec.decode(legacyCursor))
    }

    @Test
    fun `invalid cursors are rejected without echoing their input`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            AnalysisRunCursorCodec.decode("invalid-cursor")
        }

        assertEquals("Analysis Run cursor is invalid.", error.message)
    }
}
