package com.papertrail.api.evidence.parsing

import com.papertrail.api.analysis.configuration.ProviderSelection
import com.papertrail.api.citation.parsing.ParsedScientificDocument
import com.papertrail.api.citation.parsing.ParsedSection
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class DefaultCitedPaperParserTest {
    @Test
    fun `selects the Cited Paper parser pinned for the run independently of the source parser`() {
        val expected = parsedDocument("docling", "1.30.0", "Docling evidence text")
        val parser = DefaultCitedPaperParser(listOf(FixturePdfParser("docling", expected)))

        val parsed = parser.parse(
            content = byteArrayOf(1),
            mediaType = "application/pdf",
            parserSelection = ProviderSelection("docling", "1.30.0"),
        )

        assertEquals("Docling evidence text", parsed.normalizedSourceText)
        assertEquals("docling", parsed.parserId)
        assertEquals("1.30.0", parsed.parserVersion)
    }

    @Test
    fun `keeps plain-text cited-paper parsing independent of the selected PDF parser`() {
        val parser = DefaultCitedPaperParser(emptyList())

        val parsed = parser.parse(
            content = "Plain text evidence.".toByteArray(),
            mediaType = "text/plain",
            parserSelection = ProviderSelection("docling", "1.30.0"),
        )

        assertEquals("plain-text", parsed.parserId)
        assertEquals("v1", parsed.parserVersion)
        assertEquals("Plain text evidence.", parsed.normalizedSourceText)
    }

    @Test
    fun `does not silently fall back when the run-pinned PDF parser is unavailable`() {
        val parser = DefaultCitedPaperParser(listOf(FixturePdfParser("grobid", parsedDocument("grobid", "0.9.1-crf", "GROBID text"))))

        assertThrows(IllegalStateException::class.java) {
            parser.parse(
                content = byteArrayOf(1),
                mediaType = "application/pdf",
                parserSelection = ProviderSelection("docling", "1.30.0"),
            )
        }
    }

    private fun parsedDocument(parserId: String, parserVersion: String, text: String) = ParsedScientificDocument(
        parserId = parserId,
        parserVersion = parserVersion,
        normalizedSourceText = text,
        sections = listOf(ParsedSection(0, "Results", text, 0, text.length)),
        citationContexts = emptyList(),
        bibliographyEntries = emptyList(),
    )

    private class FixturePdfParser(
        override val parserId: String,
        private val parsed: ParsedScientificDocument,
    ) : CitedPaperPdfParser {
        override fun parse(pdf: ByteArray): ParsedScientificDocument = parsed
    }
}
