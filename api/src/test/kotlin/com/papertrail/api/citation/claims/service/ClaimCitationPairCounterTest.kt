package com.papertrail.api.citation.claims.service

import com.papertrail.api.citation.claims.domain.AtomicClaimCandidate
import com.papertrail.api.citation.claims.domain.CitationContextClaims
import com.papertrail.api.citation.parsing.ParsedBibliographyEntry
import com.papertrail.api.citation.parsing.ParsedCitationContext
import com.papertrail.api.citation.parsing.ParsedCitationOccurrence
import com.papertrail.api.citation.parsing.ParsedScientificDocument
import com.papertrail.api.citation.parsing.ParsedSection
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ClaimCitationPairCounterTest {
    @Test
    fun `counts repeated markers as distinct targets but ignores duplicate target keys within one marker`() {
        val text = "Prior studies support the method [1] and confirm the result [1]."
        val firstMarkerStart = text.indexOf("[1]")
        val secondMarkerStart = text.lastIndexOf("[1]")
        val parsed = ParsedScientificDocument(
            parserId = "grobid",
            parserVersion = "0.9.1-crf",
            normalizedSourceText = text,
            sections = listOf(ParsedSection(0, "Results", text, 0, text.length)),
            citationContexts = listOf(
                ParsedCitationContext(
                    sectionOrder = 0,
                    boundaryKind = "SENTENCE",
                    text = text,
                    startOffset = 0,
                    endOffset = text.length,
                    occurrences = listOf(
                        ParsedCitationOccurrence("[1]", firstMarkerStart, firstMarkerStart + 3, listOf("ref1", "ref1")),
                        ParsedCitationOccurrence("[1]", secondMarkerStart, secondMarkerStart + 3, listOf("ref1")),
                    ),
                ),
            ),
            bibliographyEntries = listOf(
                ParsedBibliographyEntry(0, "ref1", "A cited study", "A cited study", emptyList(), 2024, null, "JOURNAL_ARTICLE"),
            ),
        )
        val claims = listOf(CitationContextClaims(0, text.length, listOf(AtomicClaimCandidate(text, 0, text.length))))

        assertEquals(2L, ClaimCitationPairCounter.count(parsed, claims))
    }
}
