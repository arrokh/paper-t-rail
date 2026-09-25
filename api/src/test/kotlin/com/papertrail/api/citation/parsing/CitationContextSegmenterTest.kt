package com.papertrail.api.citation.parsing

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CitationContextSegmenterTest {
    private val segmenter = CitationContextSegmenter()

    @Test
    fun `markers in one clause share a context but semicolon-separated clauses do not`() {
        val text = "Prior work supports the method [1] and reports similar outcomes [2]; however, the control group found no effect [3]."
        val markers = listOf(
            CitationMarker(31, 34, "[1]", listOf("ref1")),
            CitationMarker(64, 67, "[2]", listOf("ref2")),
            CitationMarker(112, 115, "[3]", listOf("ref3")),
        )

        val contexts = segmenter.segment(text, markers)

        assertEquals(2, contexts.size)
        assertEquals("CLAUSE", contexts[0].boundaryKind)
        assertEquals("Prior work supports the method [1] and reports similar outcomes [2]", contexts[0].text)
        assertEquals(listOf("[1]", "[2]"), contexts[0].markers.map { it.text })
        assertEquals("however, the control group found no effect [3].", contexts[1].text)
        assertEquals(listOf("[3]"), contexts[1].markers.map { it.text })
        contexts.forEach { context ->
            context.markers.forEach { marker ->
                assertEquals(marker.text, text.substring(marker.startOffset, marker.endOffset))
            }
        }
    }

    @Test
    fun `contrastive clause connectors separate distinct citation contexts`() {
        val text = "The treatment improved symptoms [1], whereas controls remained unchanged [2]."
        val markers = listOf(
            CitationMarker(32, 35, "[1]", listOf("ref1")),
            CitationMarker(73, 76, "[2]", listOf("ref2")),
        )

        val contexts = segmenter.segment(text, markers)

        assertEquals(2, contexts.size)
        assertEquals("The treatment improved symptoms [1]", contexts[0].text)
        assertEquals("whereas controls remained unchanged [2].", contexts[1].text)
        assertEquals("CLAUSE", contexts[0].boundaryKind)
        assertEquals("CLAUSE", contexts[1].boundaryKind)
    }

    @Test
    fun `semicolons inside grouped citation markers do not split contexts`() {
        val text = "Earlier studies support this result [1; 2] and replicate it [3]."
        val markers = listOf(
            CitationMarker(36, 42, "[1; 2]", listOf("ref1", "ref2")),
            CitationMarker(60, 63, "[3]", listOf("ref3")),
        )

        val contexts = segmenter.segment(text, markers)

        assertEquals(1, contexts.size)
        assertEquals("SENTENCE_FALLBACK", contexts.single().boundaryKind)
        assertEquals(text, contexts.single().text)
        assertEquals(listOf("[1; 2]", "[3]"), contexts.single().markers.map { it.text })
        assertEquals(listOf("ref1", "ref2"), contexts.single().markers.first().referenceKeys)
    }

    @Test
    fun `ambiguous intra-sentence boundaries fall back to one sentence context`() {
        val text = "Prior work supports the method [1], which remains under discussion [2]."
        val markers = listOf(
            CitationMarker(31, 34, "[1]", listOf("ref1")),
            CitationMarker(67, 70, "[2]", listOf("ref2")),
        )

        val contexts = segmenter.segment(text, markers)

        assertEquals(1, contexts.size)
        assertEquals("SENTENCE_FALLBACK", contexts.single().boundaryKind)
        assertEquals(text, contexts.single().text)
        assertEquals(listOf("[1]", "[2]"), contexts.single().markers.map { it.text })
        assertTrue(contexts.single().markers.all { it.startOffset < it.endOffset })
    }
}
