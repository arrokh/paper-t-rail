package com.papertrail.api.analysis.recovery.service

import com.papertrail.api.analysis.recovery.domain.RecoveryPdfValidationException
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.encryption.AccessPermission
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream

class RecoveryPdfValidatorTest {
    private val validator = RecoveryPdfValidator(maxPages = 10)

    @Test
    fun `rejects an image-only PDF with an explicit no-OCR blocker`() {
        val rejection = assertThrows(RecoveryPdfValidationException::class.java) {
            validator.validate(imageOnlyPdf())
        }

        assertEquals("PDF_NO_EXTRACTABLE_TEXT", rejection.code)
        assertEquals(
            "This PDF has no selectable text. Scanned PDFs are not supported for Recovery Upload validation.",
            rejection.message,
        )
    }

    @Test
    fun `accepts a PDF with selectable text`() {
        assertDoesNotThrow { validator.validate(textPdf()) }
    }

    @Test
    fun `rejects encrypted PDFs with an actionable blocker`() {
        listOf(encryptedTextPdf("user-password"), encryptedTextPdf("")).forEach { encryptedPdf ->
            val rejection = assertThrows(RecoveryPdfValidationException::class.java) {
                validator.validate(encryptedPdf)
            }

            assertEquals("PDF_ENCRYPTED", rejection.code)
            assertEquals("Encrypted PDFs are not supported for Recovery Upload validation.", rejection.message)
        }
    }

    @Test
    fun `blocks an incomplete PDF that cannot be opened`() {
        val rejection = assertThrows(RecoveryPdfValidationException::class.java) {
            validator.validate("%PDF-1.7\n".toByteArray(Charsets.US_ASCII))
        }

        assertEquals("INVALID_PDF", rejection.code)
        assertEquals("The uploaded PDF could not be opened.", rejection.message)
    }

    private fun imageOnlyPdf(): ByteArray = PDDocument().use { document ->
        val page = PDPage(PDRectangle.LETTER)
        document.addPage(page)
        PDPageContentStream(document, page).use { stream ->
            stream.addRect(72f, 650f, 240f, 80f)
            stream.fill()
        }
        ByteArrayOutputStream().use { output ->
            document.save(output)
            output.toByteArray()
        }
    }

    private fun textPdf(): ByteArray = createTextPdf()

    private fun encryptedTextPdf(userPassword: String): ByteArray = createTextPdf(encryptedUserPassword = userPassword)

    private fun createTextPdf(encryptedUserPassword: String? = null): ByteArray = PDDocument().use { document ->
        val page = PDPage(PDRectangle.LETTER)
        document.addPage(page)
        PDPageContentStream(document, page).use { stream ->
            stream.beginText()
            stream.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 12f)
            stream.newLineAtOffset(72f, 720f)
            stream.showText("Selectable text for local recovery validation.")
            stream.endText()
        }
        if (encryptedUserPassword != null) {
            val protection = StandardProtectionPolicy("owner-password", encryptedUserPassword, AccessPermission())
            protection.encryptionKeyLength = 128
            document.protect(protection)
        }
        ByteArrayOutputStream().use { output ->
            document.save(output)
            output.toByteArray()
        }
    }
}
