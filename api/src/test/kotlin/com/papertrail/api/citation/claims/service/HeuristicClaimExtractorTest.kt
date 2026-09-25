package com.papertrail.api.citation.claims.service

import com.papertrail.api.citation.claims.domain.AtomicClaimCandidate
import com.papertrail.api.citation.claims.domain.ClaimExtractionRequest
import com.papertrail.api.citation.parsing.ParsedCitationOccurrence
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HeuristicClaimExtractorTest {
    private val extractor = HeuristicClaimExtractor()

    @Test
    fun `splits coordinated predicates while retaining shared scope and source spans`() {
        val context = "Among older adults, treatment reduced pain and improved mobility [1, 2]."
        val markerStart = context.indexOf("[1, 2]")

        val claims = extractor.extract(
            ClaimExtractionRequest(
                contextText = context,
                contextStartOffset = 400,
                occurrences = listOf(ParsedCitationOccurrence("[1, 2]", 400 + markerStart, 400 + markerStart + 6, listOf("ref1", "ref2"))),
            ),
        )

        assertEquals(
            listOf(
                "Among older adults, treatment reduced pain",
                "Among older adults, treatment improved mobility",
            ),
            claims.map(AtomicClaimCandidate::text),
        )
        assertEquals(400, claims.first().sourceStartOffset)
        assertEquals(
            "Among older adults, treatment reduced pain",
            context.substring(claims.first().sourceStartOffset - 400, claims.first().sourceEndOffset - 400),
        )
        assertEquals(
            "improved mobility",
            context.substring(claims.last().sourceStartOffset - 400, claims.last().sourceEndOffset - 400),
        )
        assertTrue(claims.all { it.sourceStartOffset >= 400 && it.sourceEndOffset <= 400 + context.length })
    }

    @Test
    fun `splits independent coordinated clauses without sharing their source spans`() {
        val context = "The intervention improved mobility and the control group reported no change [1]."
        val markerStart = context.indexOf("[1]")

        val claims = extractor.extract(
            ClaimExtractionRequest(
                contextText = context,
                contextStartOffset = 0,
                occurrences = listOf(ParsedCitationOccurrence("[1]", markerStart, markerStart + 3, listOf("ref1"))),
            ),
        )

        assertEquals(
            listOf("The intervention improved mobility", "the control group reported no change"),
            claims.map(AtomicClaimCandidate::text),
        )
        assertEquals(
            listOf("The intervention improved mobility", "the control group reported no change"),
            claims.map { context.substring(it.sourceStartOffset, it.sourceEndOffset) },
        )
    }

    @Test
    fun `keeps ambiguous negation scope together rather than rewriting it`() {
        val context = "Treatment did not improve symptoms and reduce dropout [1]."
        val markerStart = context.indexOf("[1]")

        val claims = extractor.extract(
            ClaimExtractionRequest(
                contextText = context,
                contextStartOffset = 0,
                occurrences = listOf(ParsedCitationOccurrence("[1]", markerStart, markerStart + 3, listOf("ref1"))),
            ),
        )

        assertEquals(1, claims.size)
        assertEquals("Treatment did not improve symptoms and reduce dropout", claims.single().text)
        assertEquals("Treatment did not improve symptoms and reduce dropout", context.substring(claims.single().sourceStartOffset, claims.single().sourceEndOffset))
    }

    @Test
    fun `preserves concessive qualification in the extracted claim`() {
        val context = "Although treatment improved mobility [1]."
        val markerStart = context.indexOf("[1]")

        val claims = extractor.extract(
            ClaimExtractionRequest(
                contextText = context,
                contextStartOffset = 0,
                occurrences = listOf(ParsedCitationOccurrence("[1]", markerStart, markerStart + 3, listOf("ref1"))),
            ),
        )

        assertEquals("Although treatment improved mobility", claims.single().text)
        assertEquals("Although treatment improved mobility", context.substring(claims.single().sourceStartOffset, claims.single().sourceEndOffset))
    }

    @Test
    fun `keeps coordination intact when a trailing population qualifier has ambiguous scope`() {
        val context = "Treatment reduced pain in older adults and improved mobility [1]."
        val markerStart = context.indexOf("[1]")

        val claims = extractor.extract(
            ClaimExtractionRequest(
                contextText = context,
                contextStartOffset = 0,
                occurrences = listOf(ParsedCitationOccurrence("[1]", markerStart, markerStart + 3, listOf("ref1"))),
            ),
        )

        assertEquals(1, claims.size)
        assertEquals("Treatment reduced pain in older adults and improved mobility", claims.single().text)
        assertEquals("Treatment reduced pain in older adults and improved mobility", context.substring(claims.single().sourceStartOffset, claims.single().sourceEndOffset))
    }

}
