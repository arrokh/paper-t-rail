package com.papertrail.api.citation.parsing

/** A citation callout's span in normalized section text, with its GROBID bibliography keys. */
data class CitationMarker(
    val startOffset: Int,
    val endOffset: Int,
    val text: String,
    val referenceKeys: List<String>,
)

data class SegmentedCitationContext(
    val boundaryKind: String,
    val startOffset: Int,
    val endOffset: Int,
    val text: String,
    val markers: List<CitationMarker>,
)

/**
 * Groups citation markers by the smallest boundary this deterministic parser can identify reliably.
 * Semicolons and explicit contrastive/subordinating connectors are treated as clause boundaries;
 * other punctuation is kept together and falls back to the containing sentence rather than guessing.
 */
class CitationContextSegmenter {
    fun segment(text: String, markers: List<CitationMarker>): List<SegmentedCitationContext> {
        markers.forEach { marker ->
            require(marker.startOffset >= 0 && marker.endOffset <= text.length && marker.startOffset < marker.endOffset) {
                "Citation marker span is outside its source paragraph."
            }
            require(text.substring(marker.startOffset, marker.endOffset) == marker.text) {
                "Citation marker text does not match its source span."
            }
        }
        if (markers.isEmpty()) return emptyList()

        val assigned = mutableSetOf<CitationMarker>()
        val contexts = sentenceRanges(text).flatMap { sentence ->
            val sentenceMarkers = markers.filter { it.startOffset >= sentence.first && it.endOffset <= sentence.last + 1 }
            if (sentenceMarkers.isEmpty()) return@flatMap emptyList()
            val clauses = clauseRanges(text, sentence, sentenceMarkers)
            clauses.mapNotNull { clause ->
                val clauseMarkers = sentenceMarkers.filter { it.startOffset >= clause.first && it.endOffset <= clause.last + 1 }
                if (clauseMarkers.isEmpty()) return@mapNotNull null
                assigned.addAll(clauseMarkers)
                val trimmed = trimRange(text, clause)
                SegmentedCitationContext(
                    boundaryKind = if (clauses.size > 1) "CLAUSE" else "SENTENCE_FALLBACK",
                    startOffset = trimmed.first,
                    endOffset = trimmed.last + 1,
                    text = text.substring(trimmed.first, trimmed.last + 1),
                    markers = clauseMarkers.sortedBy { it.startOffset },
                )
            }
        }
        check(assigned.size == markers.size) { "Every citation marker must belong to exactly one context." }
        return contexts.sortedBy { it.startOffset }
    }

    private fun sentenceRanges(text: String): List<IntRange> {
        val ranges = mutableListOf<IntRange>()
        var start = 0
        SENTENCE_BOUNDARY.findAll(text).forEach { match ->
            val end = match.range.first + 1
            if (start < end) ranges += start until end
            start = match.range.last + 1
        }
        if (start < text.length) ranges += start until text.length
        return ranges
    }

    private fun clauseRanges(text: String, sentence: IntRange, markers: List<CitationMarker>): List<IntRange> {
        val start = sentence.first
        val endExclusive = sentence.last + 1
        val ranges = mutableListOf<IntRange>()
        var clauseStart = start
        CLAUSE_BOUNDARY.findAll(text, start).forEach { match ->
            if (match.range.first >= endExclusive) return@forEach
            val overlapsCitationMarker = markers.any { marker ->
                match.range.first < marker.endOffset && match.range.last + 1 > marker.startOffset
            }
            if (overlapsCitationMarker) return@forEach
            val clauseEnd = match.range.first
            if (clauseStart < clauseEnd) ranges += clauseStart until clauseEnd
            clauseStart = if (text[match.range.first] == ';') {
                match.range.last + 1
            } else {
                match.range.first + 1
            }
            while (clauseStart < endExclusive && text[clauseStart].isWhitespaceCharacter()) clauseStart++
        }
        if (clauseStart < endExclusive) ranges += clauseStart until endExclusive
        return ranges.ifEmpty { listOf(sentence) }
    }

    private fun trimRange(text: String, range: IntRange): IntRange {
        var start = range.first
        var endExclusive = range.last + 1
        while (start < endExclusive && text[start].isWhitespaceCharacter()) start++
        while (endExclusive > start && text[endExclusive - 1].isWhitespaceCharacter()) endExclusive--
        return start until endExclusive
    }

    private fun Char.isWhitespaceCharacter(): Boolean = isWhitespace() || Character.isSpaceChar(this)

    companion object {
        private val SENTENCE_BOUNDARY = Regex("(?<=[.!?])\\s+(?=[A-Z\\\"“])")
        private val CLAUSE_BOUNDARY = Regex(";\\s*|,\\s+(?:but|whereas|while|although|though)\\s+", RegexOption.IGNORE_CASE)
    }
}
