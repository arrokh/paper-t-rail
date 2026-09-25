package com.papertrail.api.references

import java.util.Locale
import kotlin.math.max

data class BibliographyReference(
    val title: String?,
    val authors: List<String>,
    val year: Int?,
    val doi: String?,
    val referenceType: String,
)

data class ScholarlyWork(
    val doi: String?,
    val title: String,
    val authors: List<String>,
    val year: Int?,
)

interface ScholarlyMetadataLookup {
    fun byDoi(doi: String): ScholarlyWork?
    fun search(reference: BibliographyReference): List<ScholarlyWork>
}

enum class ReferenceResolutionStatus {
    RESOLVED,
    UNRESOLVED,
    UNSUPPORTED_REFERENCE_TYPE,
}

data class ReferenceResolutionDecision(
    val status: ReferenceResolutionStatus,
    val reasonCode: String,
    val work: ScholarlyWork? = null,
    val score: Double? = null,
    val matchMethod: String? = null,
)

data class ScholarlyMatch(
    val candidate: ScholarlyWork?,
    val score: Double?,
    val reasonCode: String,
)

object DoiNormalizer {
    private val doiPattern = Regex("^10\\.\\d{4,9}/[-._;()/:A-Z0-9]+$", RegexOption.IGNORE_CASE)

    fun normalize(value: String?): String? {
        if (value.isNullOrBlank()) return null
        val normalized = value.trim()
            .replace(Regex("^https?://(?:dx\\.)?doi\\.org/", RegexOption.IGNORE_CASE), "")
            .replace(Regex("^doi:\\s*", RegexOption.IGNORE_CASE), "")
            .trim()
        return normalized.takeIf { doiPattern.matches(it) }?.lowercase(Locale.ROOT)
    }
}

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

class ConservativeReferenceResolver(
    private val scholarlyMetadata: ScholarlyMetadataLookup,
    private val matcher: ScholarlyMetadataMatcher,
) {
    fun resolve(reference: BibliographyReference): ReferenceResolutionDecision {
        if (reference.referenceType !in SUPPORTED_REFERENCE_TYPES) {
            return ReferenceResolutionDecision(
                ReferenceResolutionStatus.UNSUPPORTED_REFERENCE_TYPE,
                "UNSUPPORTED_REFERENCE_TYPE",
            )
        }

        val doi = DoiNormalizer.normalize(reference.doi)
        if (doi != null) {
            val doiWork = scholarlyMetadata.byDoi(doi)
            if (doiWork != null && DoiNormalizer.normalize(doiWork.doi) == doi) {
                return ReferenceResolutionDecision(
                    ReferenceResolutionStatus.RESOLVED,
                    "DOI_CONFIRMED",
                    doiWork,
                    1.0,
                    "CONFIRMED_DOI",
                )
            }
            return ReferenceResolutionDecision(
                ReferenceResolutionStatus.UNRESOLVED,
                "DOI_UNCONFIRMED",
            )
        }

        if (reference.title.isNullOrBlank()) {
            return ReferenceResolutionDecision(
                ReferenceResolutionStatus.UNRESOLVED,
                "INSUFFICIENT_MATCH_METADATA",
            )
        }
        val match = matcher.match(reference, scholarlyMetadata.search(reference))
        return if (match.candidate != null) {
            ReferenceResolutionDecision(
                ReferenceResolutionStatus.RESOLVED,
                "METADATA_MATCH",
                match.candidate,
                match.score,
                "METADATA_MATCH",
            )
        } else {
            ReferenceResolutionDecision(
                ReferenceResolutionStatus.UNRESOLVED,
                match.reasonCode,
                score = match.score,
                matchMethod = "METADATA_MATCH",
            )
        }
    }

    companion object {
        val SUPPORTED_REFERENCE_TYPES = setOf(
            "JOURNAL_ARTICLE",
            "CONFERENCE_PAPER",
            "PREPRINT",
            "ACADEMIC_MANUSCRIPT",
        )
    }
}
