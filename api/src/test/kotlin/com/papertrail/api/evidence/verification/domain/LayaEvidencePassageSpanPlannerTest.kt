package com.papertrail.api.evidence.verification.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class LayaEvidencePassageSpanPlannerTest {
    private val planner = LayaEvidencePassageSpanPlanner()

    @Test
    fun `groups whole sentences and records a fitting adjacent sentence as context only`() {
        val text = "Alpha. Beta. Gamma."
        val spans = planner.plan(
            passage = passage(text),
            parentTokenCounts = tokenCounts(1200),
            tokenCountsForText = { window ->
                tokenCounts(if (window == "Alpha. Beta." || window == "Beta. Gamma.") 1024 else if (window == text) 1200 else 600)
            },
        )

        assertEquals(2, spans.size)
        assertEquals(0, spans[0].spanIndex)
        assertEquals(0, spans[0].coreStartOffset)
        assertEquals(12, spans[0].coreEndOffset)
        assertEquals(0, spans[0].contextStartOffset)
        assertEquals(12, spans[0].contextEndOffset)
        assertEquals("Alpha. Beta.", text.substring(spans[0].coreStartOffset, spans[0].coreEndOffset))
        assertEquals(13, spans[1].coreStartOffset)
        assertEquals(19, spans[1].coreEndOffset)
        assertEquals(7, spans[1].contextStartOffset)
        assertEquals(19, spans[1].contextEndOffset)
        assertEquals("Beta. Gamma.", text.substring(spans[1].contextStartOffset, spans[1].contextEndOffset))
        assertTrue(spans.zipWithNext().all { (left, right) -> left.coreEndOffset <= right.coreStartOffset })
        assertTrue(spans.all { it.tokenCounts.all { count -> count <= 1024 } })
    }

    @Test
    fun `marks an oversized single sentence incomplete without truncating it`() {
        val text = "0123456789."
        val spans = planner.plan(
            passage = passage(text),
            parentTokenCounts = tokenCounts(1025),
            tokenCountsForText = { window -> tokenCounts(if (window == text) 1025 else 1024) },
        )

        assertEquals(1, spans.size)
        assertEquals(LayaEvidencePassageSpanPlanner.SINGLE_SENTENCE_EXCEEDS_CONTEXT_LIMIT, spans.single().incompleteReason)
        assertEquals(0, spans.single().coreStartOffset)
        assertEquals(text.length, spans.single().coreEndOffset)
        assertEquals(text, text.substring(spans.single().coreStartOffset, spans.single().coreEndOffset))
        assertTrue(spans.single().tokenCounts.any { it > 1024 })
    }

    private fun passage(text: String) = EvidencePassageForJudgement(
        id = UUID.randomUUID(),
        text = text,
        sectionHeading = "Results",
    )

    private fun tokenCounts(count: Int) = List(6) { count }
}
