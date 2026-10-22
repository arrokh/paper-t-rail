package com.papertrail.api.evidence.chunking

import com.papertrail.api.citation.parsing.ParsedPageSpan
import com.papertrail.api.citation.parsing.ParsedScientificDocument
import com.papertrail.api.citation.parsing.ParsedSection
import kotlin.test.Test
import kotlin.test.assertEquals

class SectionAwareEvidenceChunkerTest {
    @Test
    fun `combines adjacent short paragraphs without crossing section boundaries`() {
        val sections = listOf(
            ParsedSection(0, "Results", "Alpha study reports\nBeta group responds\nGamma cohort improves", 0, 60),
            ParsedSection(1, "Discussion", "Delta researchers conclude", 61, 88),
        )

        val chunks = SectionAwareEvidenceChunker(targetWords = 4, maximumWords = 8).chunk(sections)

        assertEquals(
            listOf(
                ExpectedChunk(0, "Results", 1, 2, "Alpha study reports\nBeta group responds"),
                ExpectedChunk(0, "Results", 3, 3, "Gamma cohort improves"),
                ExpectedChunk(1, "Discussion", 1, 1, "Delta researchers conclude"),
            ),
            chunks.map { ExpectedChunk(it.sectionOrder, it.sectionHeading, it.paragraphStart, it.paragraphEnd, it.text) },
        )
    }

    @Test
    fun `splits an oversized paragraph into bounded chunks while retaining its location`() {
        val section = ParsedSection(0, "Methods", "alpha beta gamma delta epsilon zeta eta", 0, 39)

        val chunks = SectionAwareEvidenceChunker(targetWords = 2, maximumWords = 3).chunk(listOf(section))

        assertEquals(listOf("alpha beta gamma", "delta epsilon zeta", "eta"), chunks.map { it.text })
        assertEquals(listOf(1 to 1, 1 to 1, 1 to 1), chunks.map { it.paragraphStart to it.paragraphEnd })
    }

    @Test
    fun `keeps existing whitespace normalization for oversized passages`() {
        val section = ParsedSection(0, "Results", "alpha  beta\tgamma delta", 0, 23)

        val chunks = SectionAwareEvidenceChunker(targetWords = 2, maximumWords = 2).chunk(listOf(section))

        assertEquals(listOf("alpha beta", "gamma delta"), chunks.map { it.text })
    }

    @Test
    fun `retains every physical page touched by a passage only when provenance covers its text`() {
        val first = "The finding begins here."
        val second = "The finding continues there."
        val source = "$first\n$second"
        val section = ParsedSection(0, "Results", source, 0, source.length)
        val document = ParsedScientificDocument(
            parserId = "docling",
            parserVersion = "1.30.0",
            normalizedSourceText = source,
            sections = listOf(section),
            citationContexts = emptyList(),
            bibliographyEntries = emptyList(),
            pageSpans = listOf(
                ParsedPageSpan(0, first.length, 2),
                ParsedPageSpan(first.length + 1, source.length, 3),
            ),
        )

        val chunks = SectionAwareEvidenceChunker(targetWords = 50, maximumWords = 100).chunk(document)

        assertEquals(listOf("$first\n$second"), chunks.map { it.text })
        assertEquals(listOf(listOf(2, 3)), chunks.map { it.pageNumbers })

        val incompleteDocument = document.copy(pageSpans = listOf(ParsedPageSpan(0, 3, 2)))
        val incompleteChunks = SectionAwareEvidenceChunker(targetWords = 50, maximumWords = 100).chunk(incompleteDocument)
        assertEquals(listOf(emptyList()), incompleteChunks.map { it.pageNumbers })
    }

    private data class ExpectedChunk(
        val sectionOrder: Int,
        val sectionHeading: String?,
        val paragraphStart: Int,
        val paragraphEnd: Int,
        val text: String,
    )
}
