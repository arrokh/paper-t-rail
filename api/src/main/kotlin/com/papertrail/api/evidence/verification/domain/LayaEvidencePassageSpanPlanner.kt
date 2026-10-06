package com.papertrail.api.evidence.verification.domain

import com.papertrail.api.external.laya.LayaSystemOneSettings
import org.springframework.stereotype.Component
import java.text.BreakIterator
import java.util.Locale

/** Greedily groups complete source sentences under the exact pinned Laya request budget. */
@Component
class LayaEvidencePassageSpanPlanner {
    fun plan(
        passage: EvidencePassageForJudgement,
        parentTokenCounts: List<Int>,
        tokenCountsForText: (String) -> List<Int>,
    ): List<EvidencePassageSpanDefinition> {
        require(parentTokenCounts.size == QUESTION_SEQUENCE_COUNT && parentTokenCounts.all { it >= 0 }) {
            "Laya preflight must return counts for all six complete question sequences."
        }
        require(parentTokenCounts.any { it > LayaSystemOneSettings.MODEL_CONTEXT_TOKENS }) {
            "Only over-limit Evidence Passages can be split into spans."
        }

        val sentences = sentenceRanges(passage.text)
        require(sentences.isNotEmpty()) { "An over-limit Evidence Passage contains no sentence span." }

        val spans = mutableListOf<EvidencePassageSpanDefinition>()
        var firstCoreSentence = 0
        while (firstCoreSentence < sentences.size) {
            val coreStart = sentences[firstCoreSentence].start
            var lastCoreSentence = firstCoreSentence
            var coreEnd = sentences[lastCoreSentence].end
            var counts = checkedCounts(tokenCountsForText(passage.text.substring(coreStart, coreEnd)))

            if (!fits(counts)) {
                spans += EvidencePassageSpanDefinition(
                    spanIndex = spans.size,
                    coreStartOffset = coreStart,
                    coreEndOffset = coreEnd,
                    contextStartOffset = coreStart,
                    contextEndOffset = coreEnd,
                    tokenCounts = counts,
                    incompleteReason = SINGLE_SENTENCE_EXCEEDS_CONTEXT_LIMIT,
                )
                firstCoreSentence++
                continue
            }

            while (lastCoreSentence + 1 < sentences.size) {
                val nextEnd = sentences[lastCoreSentence + 1].end
                val expandedCounts = checkedCounts(tokenCountsForText(passage.text.substring(coreStart, nextEnd)))
                if (!fits(expandedCounts)) break
                lastCoreSentence++
                coreEnd = nextEnd
                counts = expandedCounts
            }

            var contextStart = coreStart
            var contextEnd = coreEnd
            val adjacentSentenceIndexes = buildList {
                if (firstCoreSentence > 0) add(firstCoreSentence - 1)
                if (lastCoreSentence + 1 < sentences.size) add(lastCoreSentence + 1)
            }
            for (adjacentIndex in adjacentSentenceIndexes) {
                val candidateStart = minOf(contextStart, sentences[adjacentIndex].start)
                val candidateEnd = maxOf(contextEnd, sentences[adjacentIndex].end)
                val contextCounts = checkedCounts(tokenCountsForText(passage.text.substring(candidateStart, candidateEnd)))
                if (fits(contextCounts)) {
                    contextStart = candidateStart
                    contextEnd = candidateEnd
                    counts = contextCounts
                    break
                }
            }

            spans += EvidencePassageSpanDefinition(
                spanIndex = spans.size,
                coreStartOffset = coreStart,
                coreEndOffset = coreEnd,
                contextStartOffset = contextStart,
                contextEndOffset = contextEnd,
                tokenCounts = counts,
            )
            firstCoreSentence = lastCoreSentence + 1
        }
        return spans
    }

    private fun sentenceRanges(text: String): List<SentenceRange> {
        val iterator = BreakIterator.getSentenceInstance(Locale.ROOT)
        iterator.setText(text)
        val ranges = mutableListOf<SentenceRange>()
        var boundaryStart = iterator.first()
        var boundaryEnd = iterator.next()
        while (boundaryEnd != BreakIterator.DONE) {
            var start = boundaryStart
            var end = boundaryEnd
            while (start < end && text[start].isWhitespace()) start++
            while (end > start && text[end - 1].isWhitespace()) end--
            if (start < end) ranges += SentenceRange(start, end)
            boundaryStart = boundaryEnd
            boundaryEnd = iterator.next()
        }
        return ranges
    }

    private fun checkedCounts(counts: List<Int>): List<Int> {
        require(counts.size == QUESTION_SEQUENCE_COUNT && counts.all { it >= 0 }) {
            "Laya preflight must return counts for all six complete question sequences."
        }
        return counts
    }

    private fun fits(counts: List<Int>): Boolean = counts.all { it <= LayaSystemOneSettings.MODEL_CONTEXT_TOKENS }

    private data class SentenceRange(val start: Int, val end: Int)

    companion object {
        const val SPLITTING_POLICY_VERSION = "laya-sentence-greedy-context-v1"
        const val SINGLE_SENTENCE_EXCEEDS_CONTEXT_LIMIT = "SINGLE_SENTENCE_EXCEEDS_CONTEXT_LIMIT"
        private const val QUESTION_SEQUENCE_COUNT = 6
    }
}
