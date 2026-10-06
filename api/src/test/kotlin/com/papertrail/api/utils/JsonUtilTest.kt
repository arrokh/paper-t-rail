package com.papertrail.api.utils

import com.fasterxml.jackson.core.JsonProcessingException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Instant

class JsonUtilTest {
    @Test
    fun `serializes and deserializes a typed value through static helpers`() {
        val value = JsonFixture(label = "sample", count = 3)

        val json = JsonUtil.toJson(value)

        assertEquals("""{"label":"sample","count":3}""", json)
        assertEquals(value, JsonUtil.fromJson<JsonFixture>(json))
    }

    @Test
    fun `deserializes generic collection values`() {
        val decoded = JsonUtil.fromJson<List<JsonFixture>>(
            """[{"label":"first","count":1},{"label":"second","count":2}]""",
        )

        assertEquals(listOf(JsonFixture("first", 1), JsonFixture("second", 2)), decoded)
    }

    @Test
    fun `uses the application Jackson defaults for Java time values`() {
        val value = Instant.parse("2026-10-08T12:34:56Z")

        assertEquals("\"2026-10-08T12:34:56Z\"", JsonUtil.toJson(value))
        assertEquals(value, JsonUtil.fromJson("\"2026-10-08T12:34:56Z\"", Instant::class.java))
    }

    @Test
    fun `strict tree parsing rejects duplicate keys and trailing values`() {
        assertThrows(JsonProcessingException::class.java) {
            JsonUtil.parseStrictTree("""{"value":1,"value":2}""")
        }
        assertThrows(JsonProcessingException::class.java) {
            JsonUtil.parseStrictTree("""{"value":1} {"value":2}""")
        }
    }

    private data class JsonFixture(val label: String, val count: Int)
}
