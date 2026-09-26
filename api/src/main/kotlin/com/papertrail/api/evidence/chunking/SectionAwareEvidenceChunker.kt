package com.papertrail.api.evidence.chunking

import com.papertrail.api.citation.parsing.ParsedSection
import com.papertrail.api.evidence.domain.EvidenceChunk
import org.springframework.stereotype.Component

@Component
class SectionAwareEvidenceChunker(
    private val targetWords: Int = DEFAULT_TARGET_WORDS,
    private val maximumWords: Int = DEFAULT_MAXIMUM_WORDS,
) {
    init {
        require(targetWords > 0 && maximumWords >= targetWords) { "Evidence chunk word limits are invalid." }
    }

    fun chunk(sections: List<ParsedSection>): List<EvidenceChunk> = buildList {
        sections.forEach { section ->
            val paragraphs = section.text.split('\n')
                .mapIndexedNotNull { index, text -> text.trim().takeIf(String::isNotEmpty)?.let { Paragraph(index + 1, it) } }
            var pending = mutableListOf<Paragraph>()
            var pendingWords = 0

            fun flush() {
                if (pending.isEmpty()) return
                add(
                    EvidenceChunk(
                        chunkOrder = size,
                        sectionOrder = section.sectionOrder,
                        sectionHeading = section.heading,
                        paragraphStart = pending.first().number,
                        paragraphEnd = pending.last().number,
                        text = pending.joinToString("\n") { it.text },
                    ),
                )
                pending = mutableListOf()
                pendingWords = 0
            }

            paragraphs.forEach { paragraph ->
                splitLongParagraph(paragraph).forEach { part ->
                    val partWords = wordCount(part.text)
                    if (pending.isNotEmpty() && pendingWords + partWords > maximumWords) flush()
                    pending += part
                    pendingWords += partWords
                    if (pendingWords >= targetWords) flush()
                }
            }
            flush()
        }
    }

    private fun splitLongParagraph(paragraph: Paragraph): List<Paragraph> {
        val words = paragraph.text.split(WHITESPACE)
        if (words.size <= maximumWords) return listOf(paragraph)
        return words.chunked(maximumWords).map { part -> Paragraph(paragraph.number, part.joinToString(" ")) }
    }

    private fun wordCount(text: String): Int = text.split(WHITESPACE).size

    private data class Paragraph(val number: Int, val text: String)

    companion object {
        const val DEFAULT_TARGET_WORDS = 700
        const val DEFAULT_MAXIMUM_WORDS = 900
        private val WHITESPACE = Regex("\\s+")
    }
}
