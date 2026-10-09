package com.papertrail.api.analysis.recovery.service

import com.papertrail.api.analysis.recovery.domain.RecoveryPdfValidationException
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException
import org.apache.pdfbox.text.PDFTextStripper
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.io.Writer

@Component
class RecoveryPdfValidator(
    @Value("\${paper-trail.upload.max-pages}") private val maxPages: Int,
) {
    fun validate(bytes: ByteArray) {
        if (!hasPdfSignature(bytes)) {
            throw RecoveryPdfValidationException("INVALID_PDF", "The uploaded file is not a valid PDF.")
        }

        val document = try {
            Loader.loadPDF(bytes)
        } catch (_: InvalidPasswordException) {
            throw encryptedPdfRejection()
        } catch (_: Exception) {
            throw RecoveryPdfValidationException("INVALID_PDF", "The uploaded PDF could not be opened.")
        }

        document.use { pdf ->
            if (pdf.isEncrypted) throw encryptedPdfRejection()
            when {
                pdf.numberOfPages == 0 -> throw RecoveryPdfValidationException("PDF_HAS_NO_PAGES", "The uploaded PDF has no pages.")
                pdf.numberOfPages > maxPages -> throw RecoveryPdfValidationException("PDF_TOO_MANY_PAGES", "The uploaded PDF exceeds the configured page limit.")
            }
            val textPresence = TextPresenceWriter()
            try {
                PDFTextStripper().writeText(pdf, textPresence)
            } catch (_: Exception) {
                throw RecoveryPdfValidationException("INVALID_PDF", "The uploaded PDF could not be read.")
            }
            if (!textPresence.hasText) {
                throw RecoveryPdfValidationException(
                    "PDF_NO_EXTRACTABLE_TEXT",
                    "This PDF has no selectable text. Scanned PDFs are not supported for Recovery Upload validation.",
                )
            }
        }
    }

    private class TextPresenceWriter : Writer() {
        var hasText: Boolean = false
            private set

        override fun write(buffer: CharArray, offset: Int, length: Int) {
            if (!hasText) hasText = buffer.sliceArray(offset until offset + length).any { !it.isWhitespace() }
        }

        override fun write(text: String, offset: Int, length: Int) {
            if (!hasText) hasText = text.substring(offset, offset + length).any { !it.isWhitespace() }
        }

        override fun flush() = Unit
        override fun close() = Unit
    }

    private fun encryptedPdfRejection() = RecoveryPdfValidationException(
        "PDF_ENCRYPTED",
        "Encrypted PDFs are not supported for Recovery Upload validation.",
    )

    private fun hasPdfSignature(bytes: ByteArray): Boolean {
        val signature = PDF_SIGNATURE
        val lastOffset = minOf(bytes.size - signature.size, MAX_PDF_HEADER_SEARCH_BYTES - signature.size)
        return lastOffset >= 0 && (0..lastOffset).any { offset ->
            signature.indices.all { index -> bytes[offset + index] == signature[index] }
        }
    }

    companion object {
        private const val MAX_PDF_HEADER_SEARCH_BYTES = 1024
        private val PDF_SIGNATURE = "%PDF-".toByteArray(Charsets.US_ASCII)
    }
}
