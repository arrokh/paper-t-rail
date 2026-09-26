package com.papertrail.api.scholarly.acquisition.service

import com.papertrail.api.scholarly.acquisition.domain.AcquiredFullText
import com.papertrail.api.scholarly.acquisition.domain.OpenAccessLocation
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream

class PdfBoxCitedPaperTextExtractorTest {
    @Test
    fun `extracts readable text from a legally acquired PDF`() {
        val text = extractor().extract(acquired(pdfWithText("The open paper reports reproducible research results."), "application/pdf"))

        assertTrue(text.contains("The open paper reports reproducible research results."))
    }

    @Test
    fun `rejects extracted text that exceeds the configured bound`() {
        val extractor = PdfBoxCitedPaperTextExtractor(maximumCharacters = 20)

        val exception = assertThrows(IllegalArgumentException::class.java) {
            extractor.extract(acquired(pdfWithText("123456789012345678901"), "application/pdf"))
        }

        assertTrue(exception.message.orEmpty().contains("limit"))
    }

    @Test
    fun `rejects PDFs without extractable text`() {
        val exception = assertThrows(IllegalArgumentException::class.java) {
            extractor().extract(acquired(pdfWithText(""), "application/pdf"))
        }

        assertTrue(exception.message.orEmpty().contains("no extractable text"))
    }

    private fun extractor() = PdfBoxCitedPaperTextExtractor(maximumCharacters = 1_000)

    private fun acquired(bytes: ByteArray, mediaType: String) = AcquiredFullText(
        bytes = bytes,
        mediaType = mediaType,
        location = OpenAccessLocation("https://8.8.8.8/paper.pdf", "CC-BY", "publishedVersion", "repository", "unpaywall"),
    )

    private fun pdfWithText(text: String): ByteArray = PDDocument().use { pdf ->
        val page = PDPage(PDRectangle.LETTER)
        pdf.addPage(page)
        PDPageContentStream(pdf, page).use { stream ->
            if (text.isNotEmpty()) {
                stream.beginText()
                stream.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 9f)
                stream.newLineAtOffset(35f, 740f)
                stream.showText(text)
                stream.endText()
            }
        }
        ByteArrayOutputStream().use { output ->
            pdf.save(output)
            output.toByteArray()
        }
    }
}
