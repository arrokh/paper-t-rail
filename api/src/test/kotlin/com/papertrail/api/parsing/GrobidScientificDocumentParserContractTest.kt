package com.papertrail.api.parsing

import com.papertrail.api.parsing.GrobidTeiParserTest.Companion.TEI
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.content
import org.springframework.test.web.client.match.MockRestRequestMatchers.header
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient

class GrobidScientificDocumentParserContractTest {
    @Test
    fun `submits the PDF with both external consolidation options explicitly disabled`() {
        val builder = RestClient.builder().baseUrl("http://grobid.test")
        val server = MockRestServiceServer.bindTo(builder).build()
        server.expect(requestTo("http://grobid.test/api/processFulltextDocument"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header("Content-Type", org.hamcrest.Matchers.containsString("multipart/form-data")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("name=\"input\"")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("name=\"consolidateHeader\"\r\nContent-Type: text/plain;charset=UTF-8\r\nContent-Length: 1\r\n\r\n0\r\n")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("name=\"consolidateCitations\"\r\nContent-Type: text/plain;charset=UTF-8\r\nContent-Length: 1\r\n\r\n0\r\n")))
            .andRespond(withSuccess(TEI, MediaType.APPLICATION_XML))
        val parser = GrobidScientificDocumentParser(
            builder.build(),
            GrobidTeiParser("grobid", "0.9.1-crf"),
        )

        val parsed = parser.parse("pdf-bytes".toByteArray())

        assertEquals("grobid", parsed.parserId)
        assertEquals("0.9.1-crf", parsed.parserVersion)
        assertArrayEquals(TEI.toByteArray(Charsets.UTF_8), parsed.rawParserOutput)
        server.verify()
    }

    @Test
    fun `rejects an oversized TEI body before XML parsing`() {
        val builder = RestClient.builder().baseUrl("http://grobid.test")
        val server = MockRestServiceServer.bindTo(builder).build()
        server.expect(requestTo("http://grobid.test/api/processFulltextDocument"))
            .andExpect(method(HttpMethod.POST))
            .andRespond(withSuccess(TEI, MediaType.APPLICATION_XML))
        val parser = GrobidScientificDocumentParser(
            builder.build(),
            GrobidTeiParser("grobid", "0.9.1-crf"),
            maximumResponseBytes = 32,
        )

        assertThrows(IllegalArgumentException::class.java) { parser.parse("pdf-bytes".toByteArray()) }
        server.verify()
    }
}
