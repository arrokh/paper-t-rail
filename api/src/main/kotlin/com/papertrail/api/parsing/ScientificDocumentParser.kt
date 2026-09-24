package com.papertrail.api.parsing

import org.springframework.core.io.ByteArrayResource
import org.springframework.http.MediaType
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.RestClient
import java.nio.charset.StandardCharsets

interface ScientificDocumentParser {
    fun parse(pdf: ByteArray): ParsedScientificDocument
}

class GrobidScientificDocumentParser(
    private val client: RestClient,
    private val teiParser: GrobidTeiParser,
    private val maximumResponseBytes: Int = DEFAULT_MAXIMUM_RESPONSE_BYTES,
) : ScientificDocumentParser {
    init {
        require(maximumResponseBytes > 0 && maximumResponseBytes < Int.MAX_VALUE) {
            "The scientific parser response limit must be positive and below 2 GiB."
        }
    }

    override fun parse(pdf: ByteArray): ParsedScientificDocument {
        require(pdf.isNotEmpty()) { "A non-empty PDF is required for scientific parsing." }
        val request = LinkedMultiValueMap<String, Any>().apply {
            add("input", object : ByteArrayResource(pdf) {
                override fun getFilename(): String = "source.pdf"
            })
            // Keep all GROBID consolidation disabled; bibliographic data must not be sent to external services.
            add("consolidateHeader", "0")
            add("consolidateCitations", "0")
        }
        val response = client.post()
            .uri("/api/processFulltextDocument")
            .contentType(MediaType.MULTIPART_FORM_DATA)
            .accept(MediaType.APPLICATION_XML, MediaType.TEXT_XML)
            .body(request)
            .exchange { _, response ->
                if (!response.statusCode.is2xxSuccessful) {
                    throw IllegalStateException("The scientific parser returned HTTP ${response.statusCode.value()}.")
                }
                val bytes = response.body.readNBytes(maximumResponseBytes + 1)
                require(bytes.size <= maximumResponseBytes) {
                    "The scientific parser response exceeds the configured byte limit."
                }
                bytes to (response.headers.contentType?.charset ?: StandardCharsets.UTF_8)
            } ?: throw IllegalStateException("The scientific parser returned no response.")
        val (responseBytes, responseCharset) = response
        require(responseBytes.isNotEmpty()) { "The scientific parser returned an empty response." }
        return teiParser.parse(String(responseBytes, responseCharset))
            .copy(rawParserOutput = responseBytes)
    }

    companion object {
        const val DEFAULT_MAXIMUM_RESPONSE_BYTES = 64 * 1024 * 1024
    }
}
