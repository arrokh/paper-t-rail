package com.papertrail.api.documents

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream

class PdfDocumentValidatorTest {
    private val detector = OptimaizeDocumentLanguageDetector()

    @Test
    fun `accepts an English text PDF and reports its hash and limits without truncation`() {
        val content = pdfWithText(ENGLISH.repeat(80))
        val expectedHash = contentHash(content)
        val validated = validator().validate("draft.pdf", "application/pdf", content, expectedHash)

        assertEquals("en", validated.language)
        assertEquals(1, validated.pageCount)
        assertEquals(expectedHash, validated.sha256)
        assertEquals("draft.pdf", validated.sanitizedFilename)
        assertEquals(ENGLISH.repeat(80).count { !it.isWhitespace() }, validated.extractedCharacterCount)
    }

    @Test
    fun `accepts a valid English PDF with its header within the first one thousand twenty four bytes`() {
        val content = "accepted preamble\\n".toByteArray() + pdfWithText(ENGLISH.repeat(80))
        val expectedHash = contentHash(content)

        val validated = validator().validate("draft.pdf", "application/pdf", content, expectedHash)

        assertEquals(expectedHash, validated.sha256)
        assertEquals("en", validated.language)
    }

    @Test
    fun `rejects a PDF header after the first one thousand twenty four bytes`() {
        val content = ByteArray(1024) { 0x20 } + pdfWithText(ENGLISH.repeat(80))

        val exception = assertThrows(DocumentValidationException::class.java) {
            validator().validate("draft.pdf", "application/pdf", content, contentHash(content))
        }

        assertEquals("INVALID_PDF_SIGNATURE", exception.code)
    }

    @Test
    fun `accepts short English text below the old one thousand character minimum`() {
        val content = pdfWithText(ENGLISH.repeat(5))

        val validated = validator().validate("abstract.pdf", "application/pdf", content, "a".repeat(64))

        assertEquals("en", validated.language)
        assert(validated.extractedCharacterCount < 1_000)
    }

    @Test
    fun `rejects a non-English text PDF with a clear unsupported-language reason`() {
        val exception = assertThrows(DocumentValidationException::class.java) {
            validator().validate("borrador.pdf", "application/pdf", pdfWithText(SPANISH.repeat(100)), "b".repeat(64))
        }

        assertEquals("UNSUPPORTED_LANGUAGE", exception.code)
        assert(exception.message.contains("Only English PDFs are supported"))
    }

    @Test
    fun `rejects a scanned image PDF when it has no extractable text`() {
        val exception = assertThrows(DocumentValidationException::class.java) {
            validator().validate("scan.pdf", "application/pdf", scannedPdf(), "c".repeat(64))
        }

        assertEquals("SCANNED_PDF_UNSUPPORTED", exception.code)
        assert(exception.message.contains("OCR is not supported"))
    }

    @Test
    fun `reports malformed PDFs explicitly`() {
        val exception = assertThrows(DocumentValidationException::class.java) {
            validator().validate("broken.pdf", "application/pdf", "%PDF-1.7\nnot a PDF".toByteArray(), "d".repeat(64))
        }

        assertEquals("PDF_PARSE_FAILED", exception.code)
    }

    @Test
    fun `rejects uploads beyond the configured byte limit instead of truncating`() {
        val content = pdfWithText(ENGLISH.repeat(80))
        val exception = assertThrows(DocumentValidationException::class.java) {
            validator(maxBytes = content.size.toLong() - 1).validate("draft.pdf", "application/pdf", content, "e".repeat(64))
        }

        assertEquals("UPLOAD_TOO_LARGE", exception.code)
    }

    @Test
    fun `rejects PDFs that exceed the configured extracted-text limit instead of truncating`() {
        val exception = assertThrows(DocumentValidationException::class.java) {
            validator(maxExtractedCharacters = 500).validate(
                "draft.pdf",
                "application/pdf",
                pdfWithText(ENGLISH.repeat(80)),
                "f".repeat(64),
            )
        }

        assertEquals("PDF_TEXT_TOO_LARGE", exception.code)
    }

    @Test
    fun `rejects a page above its configured extracted-text limit before collecting the whole page`() {
        val exception = assertThrows(DocumentValidationException::class.java) {
            validator(maxExtractedCharactersPerPage = 500).validate(
                "draft.pdf",
                "application/pdf",
                pdfWithText(ENGLISH.repeat(80)),
                "2".repeat(64),
            )
        }

        assertEquals("PDF_TEXT_TOO_LARGE", exception.code)
        assert(exception.message.contains("A PDF page"))
    }

    @Test
    fun `rejects a non-PDF content type even when filename appears valid`() {
        val exception = assertThrows(DocumentValidationException::class.java) {
            validator().validate("draft.pdf", "text/plain", pdfWithText(ENGLISH.repeat(80)), "1".repeat(64))
        }

        assertEquals("UNSUPPORTED_CONTENT_TYPE", exception.code)
    }

    private fun validator(
        maxBytes: Long = 10_000_000,
        maxPages: Int = 10,
        maxExtractedCharacters: Int = 20_000,
        maxExtractedCharactersPerPage: Int = 100_000,
        minCharacters: Int = 100,
        minLanguageConfidence: Double = 0.65,
    ) = PdfDocumentValidator(
        languageDetector = detector,
        maxBytes = maxBytes,
        maxPages = maxPages,
        maxExtractedCharacters = maxExtractedCharacters,
        maxExtractedCharactersPerPage = maxExtractedCharactersPerPage,
        minimumExtractedCharacters = minCharacters,
        minimumLanguageConfidence = minLanguageConfidence,
        parserId = "pdfbox",
        parserVersion = "3.0.5",
    )

    private fun pdfWithText(text: String): ByteArray {
        val document = PDDocument()
        document.use { pdf ->
            val page = PDPage(PDRectangle.LETTER)
            pdf.addPage(page)
            PDPageContentStream(pdf, page).use { stream ->
                stream.beginText()
                stream.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 9f)
                stream.newLineAtOffset(35f, 740f)
                stream.showText(text)
                stream.endText()
            }
            return ByteArrayOutputStream().use { output ->
                pdf.save(output)
                output.toByteArray()
            }
        }
    }

    private fun scannedPdf(): ByteArray {
        val document = PDDocument()
        document.use { pdf ->
            val page = PDPage(PDRectangle.LETTER)
            pdf.addPage(page)
            val image = BufferedImage(32, 32, BufferedImage.TYPE_INT_RGB)
            val imageObject = LosslessFactory.createFromImage(pdf, image)
            PDPageContentStream(pdf, page).use { it.drawImage(imageObject, 20f, 20f, 32f, 32f) }
            return ByteArrayOutputStream().use { output ->
                pdf.save(output)
                output.toByteArray()
            }
        }
    }

    private fun contentHash(content: ByteArray): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(content).joinToString("") { "%02x".format(it) }

    companion object {
        private const val ENGLISH = "This academic study examines evidence-based research and explains the results of the analysis. "
        private const val SPANISH = "Este estudio analiza la investigacion academica y presenta resultados sobre los efectos de la educacion. "
    }
}
