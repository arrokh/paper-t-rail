package com.papertrail.api.references.resolver

import com.papertrail.api.references.client.BibliographyReference
import com.papertrail.api.references.client.ScholarlyWork
import com.papertrail.api.references.normalization.DoiNormalizer
import java.util.Locale
import kotlin.math.max

/** Stable score policy: title 0.70, author list 0.20, and exact year 0.10. */
class ScholarlyMetadataMatcher(
    private val threshold: Double,
    private val ambiguityMargin: Double,
) {
    init {
        require(threshold in 0.0..1.0) { "Reference matching threshold must be between zero and one." }
        require(ambiguityMargin in 0.0..1.0) { "Reference matching ambiguity margin must be between zero and one." }
    }

    fun match(reference: BibliographyReference, candidates: List<ScholarlyWork>): ScholarlyMatch {
        val referenceTitle = normalizeText(reference.title)
            ?: return ScholarlyMatch(null, null, "INSUFFICIENT_MATCH_METADATA")
        val referenceAuthors = normalizedAuthors(reference.authors)
        val comparableAuthorData = referenceAuthors.isNotEmpty()
        val comparableYearData = reference.year != null
        if (!comparableAuthorData && !comparableYearData) {
            return ScholarlyMatch(null, null, "INSUFFICIENT_MATCH_METADATA")
        }

        val scored = candidates.mapNotNull { candidate ->
            val candidateTitle = normalizeText(candidate.title) ?: return@mapNotNull null
            val candidateAuthors = normalizedAuthors(candidate.authors)
            val titleScore = similarity(referenceTitle, candidateTitle)
            var weightedScore = titleScore * TITLE_WEIGHT
            var totalWeight = TITLE_WEIGHT
            if (comparableAuthorData) {
                weightedScore += similarity(referenceAuthors.joinToString("|"), candidateAuthors.joinToString("|")) * AUTHOR_WEIGHT
                totalWeight += AUTHOR_WEIGHT
            }
            if (comparableYearData) {
                weightedScore += (if (reference.year == candidate.year) 1.0 else 0.0) * YEAR_WEIGHT
                totalWeight += YEAR_WEIGHT
            }
            ScoredCandidate(candidate, weightedScore / totalWeight)
        }
            .distinctBy { candidateIdentity(it.work) }
            .sortedWith(compareByDescending<ScoredCandidate> { it.score }.thenBy { candidateIdentity(it.work) })

        val best = scored.firstOrNull() ?: return ScholarlyMatch(null, null, "NO_CANDIDATES")
        if (best.score < threshold) return ScholarlyMatch(null, best.score, "BELOW_CONFIDENCE_THRESHOLD")
        val second = scored.getOrNull(1)
        if (second != null && best.score - second.score <= ambiguityMargin) {
            return ScholarlyMatch(null, best.score, "AMBIGUOUS_MATCH")
        }
        return ScholarlyMatch(best.work, best.score, "MATCHED")
    }

    private fun similarity(left: String, right: String): Double {
        if (left == right) return 1.0
        if (left.isEmpty() || right.isEmpty()) return 0.0
        val previous = IntArray(right.length + 1) { it }
        val current = IntArray(right.length + 1)
        for (leftIndex in left.indices) {
            current[0] = leftIndex + 1
            for (rightIndex in right.indices) {
                val substitutionCost = if (left[leftIndex] == right[rightIndex]) 0 else 1
                current[rightIndex + 1] = minOf(
                    current[rightIndex] + 1,
                    previous[rightIndex + 1] + 1,
                    previous[rightIndex] + substitutionCost,
                )
            }
            current.copyInto(previous)
        }
        return 1.0 - previous[right.length].toDouble() / max(left.length, right.length)
    }

    private fun normalizeText(value: String?): String? = value
        ?.lowercase(Locale.ROOT)
        ?.replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        ?.trim()
        ?.takeIf(String::isNotEmpty)

    private fun normalizedAuthors(authors: List<String>): List<String> = authors
        .mapNotNull(::normalizeText)
        .distinct()
        .sorted()

    private fun candidateIdentity(work: ScholarlyWork): String = DoiNormalizer.normalize(work.doi)
        ?.let { "doi:$it" }
        ?: "metadata:${normalizeText(work.title).orEmpty()}|${normalizedAuthors(work.authors).joinToString("|")}|${work.year ?: ""}"

    private data class ScoredCandidate(val work: ScholarlyWork, val score: Double)

    companion object {
        const val POLICY_VERSION = "title-author-year-weighted-edit-similarity-v1"
        const val AMBIGUITY_MARGIN = 0.02
        private const val TITLE_WEIGHT = 0.70
        private const val AUTHOR_WEIGHT = 0.20
        private const val YEAR_WEIGHT = 0.10
    }
}
