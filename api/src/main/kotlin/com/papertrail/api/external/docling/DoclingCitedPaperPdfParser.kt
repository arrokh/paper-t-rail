package com.papertrail.api.external.docling

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.papertrail.api.citation.parsing.ParsedBibliographyEntry
import com.papertrail.api.citation.parsing.ParsedScientificDocument
import com.papertrail.api.citation.parsing.ParsedSection
import org.springframework.core.io.ByteArrayResource
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.RestClient

import com.papertrail.api.evidence.parsing.CitedPaperPdfParser

class DoclingCitedPaperPdfParser(
    private val client: RestClient,
    private val objectMapper: ObjectMapper,
    private val parserVersion: String,
    private val maximumResponseBytes: Int,
    private val maximumCharacters: Int,
) : CitedPaperPdfParser {
    init {
        require(parserVersion.isNotBlank()) { "The Docling parser version must be configured." }
        require(maximumResponseBytes > 0 && maximumResponseBytes < Int.MAX_VALUE) {
            "The Docling response limit must be positive and below 2 GiB."
        }
        require(maximumCharacters > 0) { "The Cited Paper extracted-character limit must be positive." }
    }

    override val parserId: String = PARSER_ID

    override fun parse(pdf: ByteArray): ParsedScientificDocument {
        require(pdf.isNotEmpty()) { "A non-empty Cited Paper PDF is required for parsing." }
        val request = LinkedMultiValueMap<String, Any>().apply {
            val headers = HttpHeaders().apply { contentType = MediaType.APPLICATION_PDF }
            add(
                "files",
                HttpEntity(object : ByteArrayResource(pdf) {
                    override fun getFilename(): String = "cited-paper.pdf"
                }, headers),
            )
            add("from_formats", "pdf")
            add("to_formats", "md")
        }
        val responseBytes = client.post()
            .uri("/v1/convert/file")
            .contentType(MediaType.MULTIPART_FORM_DATA)
            .accept(MediaType.APPLICATION_JSON)
            .body(request)
            .exchange { _, response ->
                if (!response.statusCode.is2xxSuccessful) {
                    throw IllegalStateException("Docling returned HTTP ${response.statusCode.value()}.")
                }
                response.body.readNBytes(maximumResponseBytes + 1)
            } ?: throw IllegalStateException("Docling returned no response.")
        require(responseBytes.size <= maximumResponseBytes) {
            "The Docling response exceeds the configured byte limit."
        }
        val response = parseResponse(responseBytes)
        if (response.path("status").asText() != SUCCESS_STATUS) {
            throw IllegalStateException("Docling could not convert the Cited Paper PDF completely.")
        }
        val markdown = response.path("document").path("md_content")
        if (!markdown.isTextual || markdown.asText().isBlank()) {
            throw IllegalStateException("Docling returned no Markdown content for the Cited Paper PDF.")
        }
        return parseMarkdown(markdown.asText())
    }

    private fun parseResponse(responseBytes: ByteArray): JsonNode = runCatching {
        objectMapper.readTree(responseBytes)
    }.getOrElse {
        throw IllegalArgumentException("Docling returned invalid JSON.")
    }

    private fun parseMarkdown(markdown: String): ParsedScientificDocument {
        val sourceSections = mutableListOf<MarkdownSection>()
        var heading: String? = null
        var paragraphs = mutableListOf<String>()
        val currentBlock = mutableListOf<String>()

        fun flushBlock() {
            if (currentBlock.isEmpty()) return
            val lines = currentBlock.toList()
            val preservesLineBreaks = lines.any(::isMarkdownTableLine) || lines.any(::isMarkdownListLine)
            val paragraph = if (preservesLineBreaks) {
                lines.joinToString("\n") { it.replace(WHITESPACE, " ").trim() }
            } else {
                lines.joinToString(" ") { it.trim() }.replace(WHITESPACE, " ").trim()
            }
            if (paragraph.isNotEmpty()) paragraphs += paragraph
            currentBlock.clear()
        }

        fun finishSection() {
            flushBlock()
            if (paragraphs.isNotEmpty()) sourceSections += MarkdownSection(heading, paragraphs)
            heading = null
            paragraphs = mutableListOf()
        }

        markdown.replace("\r\n", "\n").replace('\r', '\n').lineSequence().forEach { line ->
            val trimmed = line.trim()
            val markdownHeading = MARKDOWN_HEADING.matchEntire(trimmed)
            when {
                markdownHeading != null -> {
                    finishSection()
                    heading = markdownHeading.groupValues[1].trim().takeIf(String::isNotEmpty)
                }
                trimmed.isEmpty() -> flushBlock()
                else -> currentBlock += trimmed
            }
        }
        finishSection()

        val normalizedText = StringBuilder()
        val sections = sourceSections.mapIndexed { sectionOrder, sourceSection ->
            if (sectionOrder > 0) normalizedText.append('\n')
            val startOffset = normalizedText.length
            val text = sourceSection.paragraphs.joinToString("\n")
            normalizedText.append(text)
            require(normalizedText.length <= maximumCharacters) {
                "The Docling output exceeds the configured Cited Paper extracted-character limit."
            }
            ParsedSection(sectionOrder, sourceSection.heading, text, startOffset, normalizedText.length)
        }
        require(normalizedText.isNotBlank()) { "Docling returned no extractable Cited Paper text." }
        return ParsedScientificDocument(
            parserId = parserId,
            parserVersion = parserVersion,
            normalizedSourceText = normalizedText.toString(),
            sections = sections,
            citationContexts = emptyList(),
            bibliographyEntries = emptyList<ParsedBibliographyEntry>(),
        )
    }

    private fun isMarkdownTableLine(line: String): Boolean = line.trimStart().startsWith('|')

    private fun isMarkdownListLine(line: String): Boolean = MARKDOWN_LIST_LINE.matches(line.trimStart())

    private data class MarkdownSection(val heading: String?, val paragraphs: List<String>)

    companion object {
        const val PARSER_ID = "docling"
        private const val SUCCESS_STATUS = "success"
        private val MARKDOWN_HEADING = Regex("^#{1,6}\\s+(.+?)\\s*#*\\s*$")
        private val MARKDOWN_LIST_LINE = Regex("^(?:[-*+]\\s+|\\d+[.)]\\s+).+")
        private val WHITESPACE = Regex("[\\s\\p{Z}]+")
    }
}
