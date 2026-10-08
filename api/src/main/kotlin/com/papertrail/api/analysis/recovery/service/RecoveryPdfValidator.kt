package com.papertrail.api.analysis.recovery.service

import com.papertrail.api.analysis.recovery.domain.RecoveryPdfValidationException
import org.apache.pdfbox.Loader
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

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
        } catch (_: Exception) {
            throw RecoveryPdfValidationException("INVALID_PDF", "The uploaded PDF could not be opened.")
        }

        document.use { pdf ->
            when {
                pdf.numberOfPages == 0 -> throw RecoveryPdfValidationException("PDF_HAS_NO_PAGES", "The uploaded PDF has no pages.")
                pdf.numberOfPages > maxPages -> throw RecoveryPdfValidationException("PDF_TOO_MANY_PAGES", "The uploaded PDF exceeds the configured page limit.")
            }
        }
    }

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
