package com.papertrail.api.evidence.parsing

import com.papertrail.api.citation.parsing.ParsedBibliographicMetadataExtractionMethod
import com.papertrail.api.citation.parsing.ParsedBibliographicMetadataField
import com.papertrail.api.external.docling.DoclingCitedPaperParserConfiguration
import com.papertrail.api.external.docling.DoclingCitedPaperPdfParser
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.net.URI

@Tag("docling-integration")
class DoclingCitedPaperPdfParserRealServiceTest {
    @Test
    fun `extracts first-page metadata candidates from the pinned local Docling service`() {
        val baseUrl = System.getenv("DOCLING_TEST_BASE_URL")?.trim()?.takeIf(String::isNotEmpty)
        assumeTrue(baseUrl != null, "Set DOCLING_TEST_BASE_URL to run the real Docling contract test.")
        val configuredBaseUrl = requireNotNull(baseUrl)
        val uri = URI(configuredBaseUrl)
        assumeTrue(uri.host in LOCAL_DOCLING_HOSTS, "The Docling contract test only sends synthetic files to localhost.")

        val parser = DoclingCitedPaperPdfParser(
            client = DoclingCitedPaperParserConfiguration().doclingRestClient(configuredBaseUrl, 600_000L),
            parserVersion = "1.30.0",
            maximumResponseBytes = 64 * 1024 * 1024,
            maximumCharacters = 5_000_000,
        )
        val parsed = parser.parse(syntheticCitedPaperPdf())

        val candidates = parsed.bibliographicMetadataCandidates
        assertEquals("docling", parsed.parserId)
        assertEquals("1.30.0", parsed.parserVersion)
        assertTrue(candidates.any { it.field == ParsedBibliographicMetadataField.TITLE && it.value == TITLE })
        assertTrue(candidates.any { it.field == ParsedBibliographicMetadataField.AUTHORS && it.value == AUTHORS })
        assertTrue(candidates.any {
            it.field == ParsedBibliographicMetadataField.AUTHORS &&
                it.extractionMethod == ParsedBibliographicMetadataExtractionMethod.FIRST_TEXT_AFTER_TITLE_HEADING
        })
        assertTrue(candidates.any { it.field == ParsedBibliographicMetadataField.DOI && it.value == DOI })
        assertTrue(candidates.all { it.pageNumber == 1 })
        assertTrue(candidates.all { it.sourceElementId != null && it.sourceCharSpanStart != null && it.sourceCharSpanEnd != null })
        assertFalse(candidates.any { it.value.contains("papertrail.unrelated.reference") })
    }

    private fun syntheticCitedPaperPdf(): ByteArray = PDDocument().use { document ->
        val titlePage = PDPage(PDRectangle.LETTER)
        document.addPage(titlePage)
        PDPageContentStream(document, titlePage).use { stream ->
            stream.beginText()
            stream.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 16f)
            stream.newLineAtOffset(72f, 720f)
            stream.showText(TITLE)
            stream.newLineAtOffset(0f, -30f)
            stream.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 12f)
            stream.showText(AUTHORS)
            stream.newLineAtOffset(0f, -24f)
            stream.showText("DOI: $DOI")
            stream.newLineAtOffset(0f, -40f)
            stream.showText("Abstract")
            stream.newLineAtOffset(0f, -22f)
            stream.showText("Synthetic body text for a local Docling contract test.")
            stream.endText()
        }
        val referencesPage = PDPage(PDRectangle.LETTER)
        document.addPage(referencesPage)
        PDPageContentStream(document, referencesPage).use { stream ->
            stream.beginText()
            stream.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 12f)
            stream.newLineAtOffset(72f, 720f)
            stream.showText("References")
            stream.newLineAtOffset(0f, -24f)
            stream.showText("Unrelated cited work DOI: 10.5555/papertrail.unrelated.reference.2024")
            stream.endText()
        }
        ByteArrayOutputStream().use { output ->
            document.save(output)
            output.toByteArray()
        }
    }

    companion object {
        private val LOCAL_DOCLING_HOSTS = setOf("localhost", "127.0.0.1", "::1")
        private const val TITLE = "Synthetic Cited Paper for Local Contract Testing"
        private const val AUTHORS = "Avery Example and Rowan Sample"
        private const val DOI = "10.5555/papertrail.synthetic.article.2025"
    }
}
