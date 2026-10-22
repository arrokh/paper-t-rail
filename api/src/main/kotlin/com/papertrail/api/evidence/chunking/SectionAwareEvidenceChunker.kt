package com.papertrail.api.evidence.chunking

import com.papertrail.api.citation.parsing.ParsedPageSpan
import com.papertrail.api.citation.parsing.ParsedScientificDocument
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

    fun chunk(sections: List<ParsedSection>): List<EvidenceChunk> = chunk(sections, emptyList(), null)

    fun chunk(document: ParsedScientificDocument): List<EvidenceChunk> =
        chunk(document.sections, document.pageSpans, document.normalizedSourceText)

    private fun chunk(
        sections: List<ParsedSection>,
        pageSpans: List<ParsedPageSpan>,
        sourceText: String?,
    ): List<EvidenceChunk> = buildList {
        sections.forEach { section ->
            val paragraphs = paragraphs(section)
            var pending = mutableListOf<Paragraph>()
            var pendingWords = 0

            fun flush() {
                if (pending.isEmpty()) return
                val startOffset = pending.first().sourceStart
                val endOffset = pending.last().sourceEnd
                add(
                    EvidenceChunk(
                        chunkOrder = size,
                        sectionOrder = section.sectionOrder,
                        sectionHeading = section.heading,
                        paragraphStart = pending.first().number,
                        paragraphEnd = pending.last().number,
                        text = pending.joinToString("\n") { it.text },
                        pageNumbers = sourceText?.let { pageNumbersForRange(it, pageSpans, startOffset, endOffset) }.orEmpty(),
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

    private fun paragraphs(section: ParsedSection): List<Paragraph> {
        val paragraphs = mutableListOf<Paragraph>()
        var lineStart = 0
        section.text.split('\n').forEachIndexed { index, line ->
            val firstNonWhitespace = line.indexOfFirst { !it.isWhitespace() }
            if (firstNonWhitespace >= 0) {
                val endExclusive = line.indexOfLast { !it.isWhitespace() } + 1
                paragraphs += Paragraph(
                    number = index + 1,
                    text = line.substring(firstNonWhitespace, endExclusive),
                    sourceStart = section.startOffset + lineStart + firstNonWhitespace,
                    sourceEnd = section.startOffset + lineStart + endExclusive,
                )
            }
            lineStart += line.length + 1
        }
        return paragraphs
    }

    private fun pageNumbersForRange(
        sourceText: String,
        pageSpans: List<ParsedPageSpan>,
        startOffset: Int,
        endOffset: Int,
    ): List<Int> {
        if (startOffset < 0 || endOffset > sourceText.length || endOffset <= startOffset) return emptyList()
        val spans = pageSpans.asSequence()
            .filter { it.startOffset < endOffset && it.endOffset > startOffset }
            .sortedBy(ParsedPageSpan::startOffset)
            .toList()
        if (spans.isEmpty()) return emptyList()

        val pages = mutableSetOf<Int>()
        var spanIndex = 0
        for (offset in startOffset until endOffset) {
            if (sourceText[offset].isWhitespace()) continue
            while (spanIndex < spans.size && spans[spanIndex].endOffset <= offset) spanIndex++
            var activeIndex = spanIndex
            var pageNumber: Int? = null
            while (activeIndex < spans.size && spans[activeIndex].startOffset <= offset) {
                val span = spans[activeIndex]
                if (span.endOffset > offset) {
                    if (pageNumber != null && pageNumber != span.pageNumber) return emptyList()
                    pageNumber = span.pageNumber
                }
                activeIndex++
            }
            pages += pageNumber ?: return emptyList()
        }
        return pages.sorted()
    }

    private fun splitLongParagraph(paragraph: Paragraph): List<Paragraph> {
        val words = WORD.findAll(paragraph.text).toList()
        if (words.size <= maximumWords) return listOf(paragraph)
        return words.chunked(maximumWords).map { part ->
            val start = part.first().range.first
            val endExclusive = part.last().range.last + 1
            Paragraph(
                number = paragraph.number,
                text = part.joinToString(" ") { word -> word.value },
                sourceStart = paragraph.sourceStart + start,
                sourceEnd = paragraph.sourceStart + endExclusive,
            )
        }
    }

    private fun wordCount(text: String): Int = WORD.findAll(text).count()

    private data class Paragraph(
        val number: Int,
        val text: String,
        val sourceStart: Int,
        val sourceEnd: Int,
    )

    companion object {
        const val DEFAULT_TARGET_WORDS = 700
        const val DEFAULT_MAXIMUM_WORDS = 900
        private val WORD = Regex("\\S+")
    }
}
