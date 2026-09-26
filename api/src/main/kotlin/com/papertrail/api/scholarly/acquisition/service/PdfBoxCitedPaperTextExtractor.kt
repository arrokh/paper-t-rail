package com.papertrail.api.scholarly.acquisition.service

import com.papertrail.api.scholarly.acquisition.domain.AcquiredFullText
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.io.Writer

@Component
class PdfBoxCitedPaperTextExtractor(
    @Value("\${paper-trail.analysis.cited-paper-max-extracted-characters:5000000}") private val maximumCharacters: Int,
) : CitedPaperTextExtractor {
    init {
        require(maximumCharacters in 1..MAX_CONFIGURED_EXTRACTED_CHARACTERS) {
            "Cited-paper extracted-text limit must be between 1 character and 50 million characters."
        }
    }

    override fun extract(fullText: AcquiredFullText): String {
        val text = when (fullText.mediaType.substringBefore(';').trim().lowercase()) {
            "text/plain" -> {
                require(fullText.bytes.size.toLong() <= maximumCharacters.toLong() * MAX_UTF8_BYTES_PER_CHARACTER) {
                    "Cited full text exceeds the configured extracted-text limit."
                }
                fullText.bytes.toString(Charsets.UTF_8)
            }
            "application/pdf" -> Loader.loadPDF(fullText.bytes).use { document ->
                val text = StringBuilder(minOf(maximumCharacters, INITIAL_TEXT_CAPACITY))
                val boundedWriter = BoundedTextWriter(text, maximumCharacters)
                PDFTextStripper().writeText(document, boundedWriter)
                text.toString()
            }
            else -> throw IllegalArgumentException("Cited full text must be a PDF or plain text file.")
        }
        require(text.isNotBlank()) { "Cited full text contains no extractable text." }
        require(text.length <= maximumCharacters) { "Cited full text exceeds the configured extracted-text limit." }
        return text
    }

    private class BoundedTextWriter(
        private val output: StringBuilder,
        private val maximumCharacters: Int,
    ) : Writer() {
        override fun write(buffer: CharArray, offset: Int, length: Int) {
            require(output.length.toLong() + length <= maximumCharacters) {
                "Cited full text exceeds the configured extracted-text limit."
            }
            output.append(buffer, offset, length)
        }

        override fun write(text: String, offset: Int, length: Int) {
            require(output.length.toLong() + length <= maximumCharacters) {
                "Cited full text exceeds the configured extracted-text limit."
            }
            output.append(text, offset, offset + length)
        }

        override fun flush() = Unit
        override fun close() = Unit
    }

    companion object {
        private const val MAX_CONFIGURED_EXTRACTED_CHARACTERS = 50_000_000
        private const val MAX_UTF8_BYTES_PER_CHARACTER = 4L
        private const val INITIAL_TEXT_CAPACITY = 8192
    }
}
