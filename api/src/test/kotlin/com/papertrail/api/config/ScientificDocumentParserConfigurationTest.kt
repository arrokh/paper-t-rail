package com.papertrail.api.config

import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import com.papertrail.api.external.grobid.ScientificDocumentParserConfiguration

class ScientificDocumentParserConfigurationTest {
    private val configuration = ScientificDocumentParserConfiguration()

    @Test
    fun `accepts loopback and private Compose service addresses`() {
        configuration.grobidRestClient("http://127.0.0.1:8070")
        configuration.grobidRestClient("http://grobid:8070")
        configuration.grobidRestClient("http://host.docker.internal:8070")
    }

    @Test
    fun `rejects public GROBID destinations and credential-bearing URLs`() {
        assertThrows(IllegalArgumentException::class.java) {
            configuration.grobidRestClient("https://203.0.113.10:8070")
        }
        assertThrows(IllegalArgumentException::class.java) {
            configuration.grobidRestClient("http://user:password@127.0.0.1:8070")
        }
    }
}
