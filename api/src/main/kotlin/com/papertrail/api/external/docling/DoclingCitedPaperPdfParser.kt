package com.papertrail.api.external.docling

import com.fasterxml.jackson.databind.JsonNode
import com.papertrail.api.citation.parsing.ParsedBibliographicMetadataCandidate
import com.papertrail.api.citation.parsing.ParsedBibliographicMetadataExtractionMethod
import com.papertrail.api.citation.parsing.ParsedBibliographicMetadataField
import com.papertrail.api.citation.parsing.ParsedBibliographyEntry
import com.papertrail.api.citation.parsing.ParsedScientificDocument
import com.papertrail.api.citation.parsing.ParsedSection
import com.papertrail.api.evidence.parsing.CitedPaperPdfParser
import com.papertrail.api.utils.JsonUtil
import org.springframework.core.io.ByteArrayResource
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.RestClient

class DoclingCitedPaperPdfParser(
    private val client: RestClient,
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
            val options = BIBLIOGRAPHIC_METADATA_EXTRACTION_OPTIONS
            add("from_formats", options.getValue("from_formats"))
            options.getValue("to_formats").split(',').forEach { add("to_formats", it) }
            add("do_ocr", options.getValue("do_ocr"))
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
        return parseMarkdown(markdown.asText()).copy(
            bibliographicMetadataCandidates = parseBibliographicMetadataCandidates(
                response.path("document").path("json_content"),
            ),
            bibliographicMetadataExtractionPolicyVersion = METADATA_EXTRACTION_POLICY_VERSION,
            bibliographicMetadataExtractionOptions = BIBLIOGRAPHIC_METADATA_EXTRACTION_OPTIONS,
        )
    }

    private fun parseBibliographicMetadataCandidates(jsonContent: JsonNode): List<ParsedBibliographicMetadataCandidate> {
        val textItems = jsonContent.path("texts")
        if (!textItems.isArray) return emptyList()

        val firstPageItems = textItems.filter { item ->
            item.path("prov").any { provenance -> provenance.path("page_no").asInt(-1) == FIRST_PAGE }
        }
        val candidates = mutableListOf<ParsedBibliographicMetadataCandidate>()
        val title = firstPageItems.firstOrNull { item ->
            val text = item.path("text")
            item.path("label").asText() in TITLE_LABELS && text.isTextual && text.asText().isNotBlank()
        }
        if (title != null) {
            candidates += metadataCandidate(
                field = ParsedBibliographicMetadataField.TITLE,
                item = title,
                value = title.path("text").asText().trim(),
                extractionMethod = if (title.path("label").asText() == "title") {
                    ParsedBibliographicMetadataExtractionMethod.DOCLING_LABEL
                } else {
                    ParsedBibliographicMetadataExtractionMethod.FIRST_PAGE_SECTION_HEADER
                },
            )
            firstPageItems.drop(firstPageItems.indexOf(title) + 1)
                .firstOrNull { item ->
                    val text = item.path("text").takeIf { it.isTextual }?.asText()?.trim().orEmpty()
                    item.path("label").asText() == "text" && text.isNotEmpty() && !DOI_PATTERN.containsMatchIn(text)
                }
                ?.let { authors ->
                    candidates += metadataCandidate(
                        field = ParsedBibliographicMetadataField.AUTHORS,
                        item = authors,
                        value = authors.path("text").asText().trim(),
                        extractionMethod = ParsedBibliographicMetadataExtractionMethod.FIRST_TEXT_AFTER_TITLE_HEADING,
                    )
                }
        }

        firstPageItems.forEach { item ->
            val label = item.path("label").asText()
            val textNode = item.path("text")
            val text = textNode.takeIf { it.isTextual }?.asText()?.trim().orEmpty()
            DOI_PATTERN.findAll(text).forEach { match ->
                val doi = match.groupValues[1].trimEnd('.', ',', ';')
                if (doi.isNotEmpty()) {
                    candidates += metadataCandidate(
                        field = ParsedBibliographicMetadataField.DOI,
                        item = item,
                        value = doi,
                        extractionMethod = ParsedBibliographicMetadataExtractionMethod.EXPLICIT_DOI_PREFIX,
                    )
                }
            }
        }
        return candidates.distinct()
    }

    private fun metadataCandidate(
        field: ParsedBibliographicMetadataField,
        item: JsonNode,
        value: String,
        extractionMethod: ParsedBibliographicMetadataExtractionMethod,
    ): ParsedBibliographicMetadataCandidate {
        val provenance = item.path("prov").firstOrNull { it.path("page_no").asInt(-1) == FIRST_PAGE }
        val charSpan = provenance?.path("charspan")?.takeIf { it.isArray && it.size() >= 2 }
        return ParsedBibliographicMetadataCandidate(
            field = field,
            value = value,
            pageNumber = FIRST_PAGE,
            sourceLabel = item.path("label").asText(),
            extractionMethod = extractionMethod,
            sourceElementId = item.path("self_ref").asText().takeIf { it.isNotBlank() },
            sourceCharSpanStart = charSpan?.get(0)?.takeIf { it.canConvertToInt() }?.asInt(),
            sourceCharSpanEnd = charSpan?.get(1)?.takeIf { it.canConvertToInt() }?.asInt(),
        )
    }

    private fun parseResponse(responseBytes: ByteArray): JsonNode = runCatching {
        JsonUtil.parseTree(responseBytes)
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
        private const val FIRST_PAGE = 1
        const val METADATA_EXTRACTION_POLICY_VERSION = "docling-first-page-metadata-candidates-v1"
        val BIBLIOGRAPHIC_METADATA_EXTRACTION_OPTIONS = mapOf(
            "from_formats" to "pdf",
            "to_formats" to "md,json",
            "do_ocr" to "false",
        )
        private val TITLE_LABELS = setOf("title", "section_header")
        private val DOI_PATTERN = Regex("(?:\\bdoi\\s*:\\s*|\\bhttps?://(?:dx\\.)?doi\\.org/)(10\\.[0-9]{4,9}/[^\\s<>\"{}|\\\\^`\\[\\]]+)", RegexOption.IGNORE_CASE)
        private val MARKDOWN_HEADING = Regex("^#{1,6}\\s+(.+?)\\s*#*\\s*$")
        private val MARKDOWN_LIST_LINE = Regex("^(?:[-*+]\\s+|\\d+[.)]\\s+).+")
        private val WHITESPACE = Regex("[\\s\\p{Z}]+")
    }
}
