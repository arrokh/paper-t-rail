package com.papertrail.api.document.validation

import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.pdfbox.text.TextPosition
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import java.io.Writer
import java.util.Locale

@Component
class PdfDocumentValidator(
    private val languageDetector: DocumentLanguageDetector,
    @Value("\${paper-trail.upload.max-bytes}") maxBytes: Long,
    @Value("\${paper-trail.upload.max-pages}") maxPages: Int,
    @Value("\${paper-trail.upload.max-extracted-characters}") maxExtractedCharacters: Int,
    @Value("\${paper-trail.upload.max-extracted-characters-per-page}") maxExtractedCharactersPerPage: Int,
    @Value("\${paper-trail.upload.minimum-extracted-characters}") minimumExtractedCharacters: Int,
    @Value("\${paper-trail.upload.minimum-language-confidence}") minimumLanguageConfidence: Double,
    @Value("\${paper-trail.validation.parser-id}") val parserId: String,
    @Value("\${paper-trail.validation.parser-version}") val parserVersion: String,
) {
    val limits = PdfValidationLimits(
        maxBytes,
        maxPages,
        maxExtractedCharacters,
        maxExtractedCharactersPerPage,
        minimumExtractedCharacters,
        minimumLanguageConfidence,
    )

    fun validate(
        filename: String?,
        contentType: String?,
        bytes: ByteArray,
        sha256: String,
    ): ValidatedPdf {
        if (bytes.isEmpty()) reject("EMPTY_UPLOAD", "Choose a non-empty PDF file.")
        if (bytes.size.toLong() > limits.maxBytes) {
            reject("UPLOAD_TOO_LARGE", "The PDF exceeds the configured ${limits.maxBytes}-byte upload limit.")
        }
        val safeFilename = sanitizeFilename(filename)
        if (!safeFilename.endsWith(".pdf", ignoreCase = true)) {
            reject("PDF_FILENAME_REQUIRED", "The uploaded document must have a .pdf filename.")
        }
        if (contentType != null && contentType !in setOf(MediaType.APPLICATION_PDF_VALUE, MediaType.APPLICATION_OCTET_STREAM_VALUE)) {
            reject("UNSUPPORTED_CONTENT_TYPE", "Only PDF documents are supported.")
        }
        if (!hasPdfSignature(bytes)) {
            reject("INVALID_PDF_SIGNATURE", "The file does not have a valid PDF signature.")
        }

        val document = try {
            Loader.loadPDF(bytes)
        } catch (exception: Exception) {
            reject("PDF_PARSE_FAILED", "The PDF could not be parsed. Check that it is not damaged or encrypted.")
        }

        document.use { pdf ->
            val pageCount = pdf.numberOfPages
            if (pageCount == 0) reject("PDF_HAS_NO_PAGES", "The PDF contains no pages.")
            if (pageCount > limits.maxPages) {
                reject("PDF_TOO_MANY_PAGES", "The PDF has $pageCount pages; the configured limit is ${limits.maxPages}.")
            }

            val text = extractAllText(pdf)
            val visibleCharacterCount = text.count { !it.isWhitespace() && !it.isISOControl() }
            if (visibleCharacterCount < limits.minimumExtractedCharacters) {
                val scanned = hasEmbeddedImages(pdf)
                if (scanned) {
                    reject("SCANNED_PDF_UNSUPPORTED", "This PDF appears to contain scanned page images without enough extractable text. OCR is not supported.")
                }
                reject("PDF_HAS_INSUFFICIENT_TEXT", "The PDF contains only $visibleCharacterCount extractable characters; at least ${limits.minimumExtractedCharacters} are required.")
            }

            val detection = languageDetector.detect(text)
            if (detection.language != "en" || detection.confidence < limits.minimumLanguageConfidence) {
                val detected = detection.language?.let(Locale::forLanguageTag)?.getDisplayLanguage(Locale.ENGLISH)
                val reason = if (detected == null) "could not be determined" else "appears to be $detected"
                reject(
                    "UNSUPPORTED_LANGUAGE",
                    "Only English PDFs are supported; the document $reason (confidence ${"%.2f".format(Locale.ROOT, detection.confidence)}).",
                )
            }

            return ValidatedPdf(
                sanitizedFilename = safeFilename,
                sha256 = sha256,
                pageCount = pageCount,
                language = "en",
                extractedCharacterCount = visibleCharacterCount,
            )
        }
    }

    private fun extractAllText(document: PDDocument): String {
        val output = CharacterLimitWriter(limits.maxExtractedCharacters)
        val stripper = BoundedPdfTextStripper(limits.maxExtractedCharacters, limits.maxExtractedCharactersPerPage)
        try {
            stripper.writeText(document, output)
        } catch (exception: ExtractedTextLimitExceeded) {
            val reason = when (exception.limit) {
                ExtractionLimit.PAGE -> "A PDF page contains more than the configured ${limits.maxExtractedCharactersPerPage} extracted-character limit."
                ExtractionLimit.TOTAL -> "The PDF contains more than the configured ${limits.maxExtractedCharacters} extracted-character limit."
            }
            reject("PDF_TEXT_TOO_LARGE", reason)
        } catch (exception: Exception) {
            reject("PDF_PARSE_FAILED", "Text could not be extracted from the PDF.")
        }
        return output.text()
    }

    private fun hasEmbeddedImages(document: PDDocument): Boolean = document.pages.any { page ->
        val resources = page.resources ?: return@any false
        resources.xObjectNames.any { name ->
            runCatching { resources.getXObject(name) is PDImageXObject }.getOrDefault(false)
        }
    }

    private fun sanitizeFilename(filename: String?): String {
        val leaf = filename?.replace('\\', '/')?.substringAfterLast('/')?.trim().orEmpty()
        val cleaned = leaf.map { character ->
            if (character.isLetterOrDigit() || character in " ._-()") character else '_'
        }.joinToString("").trim().trim('.')
        return cleaned.take(120).ifBlank { "upload.pdf" }
    }

    private fun hasPdfSignature(bytes: ByteArray): Boolean {
        val lastOffset = minOf(bytes.size - PDF_SIGNATURE.size, MAX_PDF_HEADER_SEARCH_BYTES - PDF_SIGNATURE.size)
        return lastOffset >= 0 && (0..lastOffset).any { offset ->
            PDF_SIGNATURE.indices.all { index -> bytes[offset + index] == PDF_SIGNATURE[index] }
        }
    }

    private fun reject(code: String, reason: String): Nothing = throw DocumentValidationException(code, reason)

    private class BoundedPdfTextStripper(
        private val maximumCharacters: Int,
        private val maximumCharactersPerPage: Int,
    ) : PDFTextStripper() {
        private var observedCharacters = 0
        private var observedCharactersOnPage = 0

        override fun startPage(page: PDPage) {
            observedCharactersOnPage = 0
            super.startPage(page)
        }

        override fun processTextPosition(text: TextPosition) {
            val characterCount = text.unicode?.length?.coerceAtLeast(1) ?: 1
            observedCharactersOnPage += characterCount
            if (observedCharactersOnPage > maximumCharactersPerPage) throw ExtractedTextLimitExceeded(ExtractionLimit.PAGE)
            observedCharacters += characterCount
            if (observedCharacters > maximumCharacters) throw ExtractedTextLimitExceeded(ExtractionLimit.TOTAL)
            super.processTextPosition(text)
        }
    }

    private class CharacterLimitWriter(private val maximumCharacters: Int) : Writer() {
        private val buffer = StringBuilder()

        override fun write(characters: CharArray, offset: Int, length: Int) {
            if (buffer.length + length > maximumCharacters) throw ExtractedTextLimitExceeded(ExtractionLimit.TOTAL)
            buffer.append(characters, offset, length)
        }

        override fun flush() = Unit
        override fun close() = Unit
        fun text(): String = buffer.toString()
    }

    private enum class ExtractionLimit { PAGE, TOTAL }

    private class ExtractedTextLimitExceeded(val limit: ExtractionLimit) : RuntimeException()

    companion object {
        private const val MAX_PDF_HEADER_SEARCH_BYTES = 1024
        private val PDF_SIGNATURE = "%PDF-".toByteArray(Charsets.US_ASCII)
    }
}
