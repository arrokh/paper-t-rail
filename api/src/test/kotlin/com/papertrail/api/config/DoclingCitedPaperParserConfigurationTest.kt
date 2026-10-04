package com.papertrail.api.config

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.papertrail.api.evidence.parsing.DoclingCitedPaperPdfParser
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicBoolean

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
    fun `uses HTTP 1 1 for Docling multipart requests`() {
        val sawUpgradeRequest = AtomicBoolean(false)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/convert/file") { exchange ->
            exchange.requestBody.use { it.readBytes() }
            if (exchange.requestHeaders.getFirst("Upgrade") != null) {
                sawUpgradeRequest.set(true)
                exchange.sendResponseHeaders(400, -1)
            } else {
                val response = """{"status":"success","document":{"md_content":"## Results\n\nSynthetic evidence."}}"""
                    .toByteArray(Charsets.UTF_8)
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, response.size.toLong())
                exchange.responseBody.use { it.write(response) }
            }
            exchange.close()
        }
        server.start()

        try {
            val client = configuration.doclingRestClient("http://127.0.0.1:${server.address.port}", 10_000)
            val parser = DoclingCitedPaperPdfParser(
                client = client,
                objectMapper = jacksonObjectMapper(),
                parserVersion = "1.30.0",
                maximumResponseBytes = 64 * 1024,
                maximumCharacters = 5_000,
            )

            val parsed = parser.parse(byteArrayOf(0x25, 0x50, 0x44, 0x46))

            assertEquals("Synthetic evidence.", parsed.normalizedSourceText)
            assertFalse(sawUpgradeRequest.get())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `requires a positive request timeout`() {
        assertThrows(IllegalArgumentException::class.java) {
            configuration.doclingRestClient("http://127.0.0.1:5001", 0)
        }
    }
}
