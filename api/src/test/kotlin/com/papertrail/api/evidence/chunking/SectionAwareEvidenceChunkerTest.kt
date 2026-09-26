package com.papertrail.api.evidence.chunking

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

    private data class ExpectedChunk(
        val sectionOrder: Int,
        val sectionHeading: String?,
        val paragraphStart: Int,
        val paragraphEnd: Int,
        val text: String,
    )
}
