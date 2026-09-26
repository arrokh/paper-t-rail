package com.papertrail.api.evidence.parsing

import com.papertrail.api.citation.parsing.ParsedBibliographyEntry
import com.papertrail.api.citation.parsing.ParsedScientificDocument
import com.papertrail.api.citation.parsing.ParsedSection
import com.papertrail.api.citation.parsing.ScientificDocumentParser
import org.springframework.stereotype.Component

interface CitedPaperParser {
    fun parse(content: ByteArray, mediaType: String): ParsedScientificDocument
}

@Component
class DefaultCitedPaperParser(
    private val scientificDocumentParser: ScientificDocumentParser,
) : CitedPaperParser {
    override fun parse(content: ByteArray, mediaType: String): ParsedScientificDocument = when (mediaType.substringBefore(';').trim().lowercase()) {
        "application/pdf" -> scientificDocumentParser.parse(content)
        "text/plain" -> parsePlainText(content)
        else -> throw IllegalArgumentException("Cited full text must be a PDF or plain-text file.")
    }

    private fun parsePlainText(content: ByteArray): ParsedScientificDocument {
        val text = content.toString(Charsets.UTF_8)
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .lineSequence()
            .map(String::trim)
            .joinToString("\n")
            .trim()
        require(text.isNotBlank()) { "Cited full text contains no extractable text." }
        return ParsedScientificDocument(
            parserId = PLAIN_TEXT_PARSER_ID,
            parserVersion = PLAIN_TEXT_PARSER_VERSION,
            normalizedSourceText = text,
            sections = listOf(ParsedSection(0, null, text, 0, text.length)),
            citationContexts = emptyList(),
            bibliographyEntries = emptyList<ParsedBibliographyEntry>(),
        )
    }

    companion object {
        const val PLAIN_TEXT_PARSER_ID = "plain-text"
        const val PLAIN_TEXT_PARSER_VERSION = "v1"
    }
}
