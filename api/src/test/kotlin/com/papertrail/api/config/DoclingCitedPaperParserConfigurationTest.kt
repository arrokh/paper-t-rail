package com.papertrail.api.config

import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class DoclingCitedPaperParserConfigurationTest {
    private val configuration = DoclingCitedPaperParserConfiguration()

    @Test
    fun `accepts localhost and private Compose service URLs`() {
        configuration.doclingRestClient("http://127.0.0.1:5001", 60_000)
        configuration.doclingRestClient("http://docling:5001", 60_000)
        configuration.doclingRestClient("http://host.docker.internal:5001", 60_000)
    }

    @Test
    fun `rejects public Docling destinations and credential-bearing URLs`() {
        assertThrows(IllegalArgumentException::class.java) {
            configuration.doclingRestClient("https://203.0.113.10:5001", 60_000)
        }
        assertThrows(IllegalArgumentException::class.java) {
            configuration.doclingRestClient("http://user:password@127.0.0.1:5001", 60_000)
        }
    }

    @Test
    fun `requires a positive request timeout`() {
        assertThrows(IllegalArgumentException::class.java) {
            configuration.doclingRestClient("http://127.0.0.1:5001", 0)
        }
    }
}
