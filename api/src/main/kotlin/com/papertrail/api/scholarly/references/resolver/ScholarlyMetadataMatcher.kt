package com.papertrail.api.scholarly.references.resolver

import com.papertrail.api.scholarly.references.client.BibliographyReference
import com.papertrail.api.scholarly.references.client.ScholarlyWork
import com.papertrail.api.scholarly.references.normalization.DoiNormalizer
import java.util.Locale
import kotlin.math.max

/** Scores candidates for inspection while applying the run-pinned identity policy. */
class ScholarlyMetadataMatcher(
    private val threshold: Double,
    private val ambiguityMargin: Double,
    val policyVersion: String = POLICY_VERSION,
) {
    init {
        require(threshold in 0.0..1.0) { "Reference matching threshold must be between zero and one." }
        require(ambiguityMargin in 0.0..1.0) { "Reference matching ambiguity margin must be between zero and one." }
        require(policyVersion in SUPPORTED_POLICY_VERSIONS) { "Reference matching policy version is not supported." }
    }

    fun match(reference: BibliographyReference, candidates: List<ScholarlyWork>): ScholarlyMatch =
        when (policyVersion) {
            LEGACY_POLICY_VERSION -> legacyMatch(reference, candidates)
            POLICY_VERSION -> conservativeMatch(reference, candidates)
            else -> error("Reference matching policy version is not supported.")
        }

    fun insufficientMetadataReason(reference: BibliographyReference): String? = when {
        normalizeText(reference.title) == null -> "INSUFFICIENT_MATCH_METADATA"
        normalizedAuthors(reference.authors).isEmpty() && reference.year == null -> "INSUFFICIENT_MATCH_METADATA"
        else -> null
    }

    fun identifierConflictReason(reference: BibliographyReference, candidate: ScholarlyWork): String? {
        if (policyVersion == LEGACY_POLICY_VERSION) return null

        val referenceTitle = normalizeText(reference.title)
        val candidateTitle = normalizeText(candidate.title)
        if (referenceTitle != null && candidateTitle != null && referenceTitle != candidateTitle) {
            return "DOI_TITLE_CONFLICT"
        }

        val referenceAuthors = normalizedAuthors(reference.authors)
        val candidateAuthors = normalizedAuthors(candidate.authors)
        if (referenceAuthors.isNotEmpty() && candidateAuthors.isNotEmpty() && !authorSetMatches(reference.authors, candidate.authors)) {
            return "DOI_AUTHOR_CONFLICT"
        }

        if (reference.year != null && candidate.year != null && reference.year != candidate.year) {
            return "DOI_YEAR_CONFLICT"
        }

        return null
    }

    fun identifierCandidateEvidence(
        reference: BibliographyReference,
        candidate: ScholarlyWork,
        outcomeCode: String,
    ): List<ScholarlyCandidateEvidence> = if (policyVersion == LEGACY_POLICY_VERSION) {
        emptyList()
    } else {
        listOf(toCandidateEvidence(reference, candidate, rankingScore = null, outcomeCode = outcomeCode))
    }

    private fun conservativeMatch(reference: BibliographyReference, candidates: List<ScholarlyWork>): ScholarlyMatch {
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
            ScoredCandidate(
                work = candidate,
                score = score(referenceTitle, reference.authors, reference.year, candidate, comparableAuthorData, comparableYearData),
                titleMatches = referenceTitle == candidateTitle,
                authorMatches = !comparableAuthorData || authorSetMatches(reference.authors, candidate.authors),
                yearMatches = !comparableYearData || candidate.year == reference.year,
            )
        }
            .sortedWith(compareByDescending<ScoredCandidate> { it.score }.thenBy { candidateIdentity(it.work) })
            .let(::deduplicateSameWorkRecords)

        val bestCandidate = scored.firstOrNull() ?: return ScholarlyMatch(null, null, "NO_CANDIDATES")
        val exactTitleCandidates = scored.filter(ScoredCandidate::titleMatches)
        if (exactTitleCandidates.isEmpty()) {
            return ScholarlyMatch(
                candidate = null,
                score = bestCandidate.score,
                reasonCode = "TITLE_CONFLICT",
                candidateEvidence = toCandidateEvidence(reference, scored),
            )
        }

        val corroboratedCandidates = exactTitleCandidates.filter { it.authorMatches && it.yearMatches }
        if (corroboratedCandidates.isEmpty()) {
            val bestTitleCandidate = exactTitleCandidates.first()
            return ScholarlyMatch(
                candidate = null,
                score = bestTitleCandidate.score,
                reasonCode = candidateConflictReason(reference, bestTitleCandidate),
                candidateEvidence = toCandidateEvidence(reference, scored),
            )
        }

        val best = corroboratedCandidates.first()
        val evidence = toCandidateEvidence(reference, scored)
        if (best.score < threshold) {
            return ScholarlyMatch(null, best.score, "BELOW_CONFIDENCE_THRESHOLD", evidence)
        }
        val second = corroboratedCandidates.getOrNull(1)
        if (second != null && best.score - second.score <= ambiguityMargin) {
            return ScholarlyMatch(null, best.score, "AMBIGUOUS_MATCH", evidence)
        }
        return ScholarlyMatch(best.work, best.score, "MATCHED", evidence)
    }

    private fun candidateConflictReason(reference: BibliographyReference, candidate: ScoredCandidate): String = when {
        normalizedAuthors(reference.authors).isNotEmpty() && normalizedAuthors(candidate.work.authors).isEmpty() -> "CANDIDATE_AUTHORS_MISSING"
        normalizedAuthors(reference.authors).isNotEmpty() && !candidate.authorMatches -> "AUTHOR_CONFLICT"
        reference.year != null && candidate.work.year == null -> "CANDIDATE_YEAR_MISSING"
        reference.year != null && !candidate.yearMatches -> "YEAR_CONFLICT"
        else -> "BIBLIOGRAPHIC_CONFLICT"
    }

    private fun legacyMatch(reference: BibliographyReference, candidates: List<ScholarlyWork>): ScholarlyMatch {
        val referenceTitle = normalizeText(reference.title)
            ?: return ScholarlyMatch(null, null, "INSUFFICIENT_MATCH_METADATA")
        val referenceAuthors = normalizedAuthors(reference.authors)
        val comparableAuthorData = referenceAuthors.isNotEmpty()
        val comparableYearData = reference.year != null
        if (!comparableAuthorData && !comparableYearData) {
            return ScholarlyMatch(null, null, "INSUFFICIENT_MATCH_METADATA")
        }

        val scored = candidates.mapNotNull { candidate ->
            if (normalizeText(candidate.title) == null) return@mapNotNull null
            ScoredCandidate(
                work = candidate,
                score = score(referenceTitle, reference.authors, reference.year, candidate, comparableAuthorData, comparableYearData),
                titleMatches = true,
                authorMatches = true,
                yearMatches = true,
            )
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

    private fun score(
        referenceTitle: String,
        referenceAuthors: List<String>,
        referenceYear: Int?,
        candidate: ScholarlyWork,
        comparableAuthorData: Boolean,
        comparableYearData: Boolean,
    ): Double {
        val candidateTitle = normalizeText(candidate.title) ?: return 0.0
        var weightedScore = similarity(referenceTitle, candidateTitle) * TITLE_WEIGHT
        var totalWeight = TITLE_WEIGHT
        if (comparableAuthorData) {
            val authorScore = if (policyVersion == POLICY_VERSION) {
                similarity(authorIdentities(referenceAuthors).joinToString("|"), authorIdentities(candidate.authors).joinToString("|"))
            } else {
                similarity(normalizedAuthors(referenceAuthors).joinToString("|"), normalizedAuthors(candidate.authors).joinToString("|"))
            }
            weightedScore += authorScore * AUTHOR_WEIGHT
            totalWeight += AUTHOR_WEIGHT
        }
        if (comparableYearData) {
            weightedScore += (if (referenceYear == candidate.year) 1.0 else 0.0) * YEAR_WEIGHT
            totalWeight += YEAR_WEIGHT
        }
        return weightedScore / totalWeight
    }

    private fun toCandidateEvidence(reference: BibliographyReference, candidates: List<ScoredCandidate>): List<ScholarlyCandidateEvidence> =
        candidates
            .sortedWith(
                compareByDescending<ScoredCandidate> { it.titleMatches }
                    .thenByDescending { it.authorMatches && it.yearMatches }
                    .thenByDescending(ScoredCandidate::score)
                    .thenBy { candidateIdentity(it.work) },
            )
            .take(MAX_CANDIDATE_EVIDENCE)
            .map { candidate ->
                toCandidateEvidence(reference, candidate.work, candidate.score, null)
            }

    private fun toCandidateEvidence(
        reference: BibliographyReference,
        candidate: ScholarlyWork,
        rankingScore: Double?,
        outcomeCode: String?,
    ): ScholarlyCandidateEvidence {
        val referenceTitle = normalizeText(reference.title)
        val candidateTitle = normalizeText(candidate.title)
        val referenceAuthors = normalizedAuthors(reference.authors)
        val candidateAuthors = normalizedAuthors(candidate.authors)
        val reasonCodes = buildList {
            add(
                when {
                    referenceTitle == null -> "REFERENCE_TITLE_MISSING"
                    candidateTitle == null -> "CANDIDATE_TITLE_MISSING"
                    referenceTitle == candidateTitle -> "TITLE_EXACT"
                    else -> "TITLE_CONFLICT"
                },
            )
            add(
                when {
                    referenceAuthors.isEmpty() -> "REFERENCE_AUTHORS_MISSING"
                    candidateAuthors.isEmpty() -> "CANDIDATE_AUTHORS_MISSING"
                    authorSetMatches(reference.authors, candidate.authors) -> "AUTHOR_SET_MATCH"
                    else -> "AUTHOR_CONFLICT"
                },
            )
            add(
                when {
                    reference.year == null -> "REFERENCE_YEAR_MISSING"
                    candidate.year == null -> "CANDIDATE_YEAR_MISSING"
                    reference.year == candidate.year -> "YEAR_MATCH"
                    else -> "YEAR_CONFLICT"
                },
            )
            outcomeCode?.let(::add)
        }
        return ScholarlyCandidateEvidence(
            doi = DoiNormalizer.normalize(candidate.doi),
            title = candidate.title,
            authors = candidate.authors,
            year = candidate.year,
            rankingScore = rankingScore,
            reasonCodes = reasonCodes,
        )
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

    private fun authorSetMatches(referenceAuthors: List<String>, candidateAuthors: List<String>): Boolean {
        val referenceIdentities = authorIdentities(referenceAuthors)
        val candidateIdentities = authorIdentities(candidateAuthors)
        return referenceIdentities.isNotEmpty() && referenceIdentities == candidateIdentities
    }

    private fun authorIdentities(authors: List<String>): List<String> = authors
        .mapNotNull(::authorIdentity)
        .distinct()
        .sorted()

    private fun authorIdentity(value: String): String? {
        val normalized = normalizeText(value) ?: return null
        val comma = value.indexOf(',')
        val surname = if (comma >= 0) normalizeText(value.substring(0, comma)) else normalized.split(' ').lastOrNull()
        val givenNames = if (comma >= 0) normalizeText(value.substring(comma + 1)) else normalized.split(' ').dropLast(1).joinToString(" ")
        if (surname.isNullOrBlank() || givenNames.isNullOrBlank()) return "full:$normalized"
        val initials = givenNames.split(' ').mapNotNull(String::firstOrNull).joinToString("")
        return "$surname|$initials"
    }

    private fun deduplicateSameWorkRecords(candidates: List<ScoredCandidate>): List<ScoredCandidate> {
        val uniqueCandidates = mutableListOf<ScoredCandidate>()
        candidates.forEach { candidate ->
            if (uniqueCandidates.none { sameWorkRecord(it.work, candidate.work) }) {
                uniqueCandidates += candidate
            }
        }
        return uniqueCandidates
    }

    private fun sameWorkRecord(left: ScholarlyWork, right: ScholarlyWork): Boolean {
        val leftDoi = DoiNormalizer.normalize(left.doi)
        val rightDoi = DoiNormalizer.normalize(right.doi)
        if (leftDoi != null && rightDoi != null) return leftDoi == rightDoi
        return metadataIdentity(left) == metadataIdentity(right)
    }

    private fun candidateIdentity(work: ScholarlyWork): String = DoiNormalizer.normalize(work.doi)
        ?.let { "doi:$it" }
        ?: "metadata:${metadataIdentity(work)}"

    private fun metadataIdentity(work: ScholarlyWork): String =
        "${normalizeText(work.title).orEmpty()}|${normalizedAuthors(work.authors).joinToString("|")}|${work.year ?: ""}"

    private data class ScoredCandidate(
        val work: ScholarlyWork,
        val score: Double,
        val titleMatches: Boolean,
        val authorMatches: Boolean,
        val yearMatches: Boolean,
    )

    companion object {
        const val POLICY_VERSION = "title-author-year-strict-consistency-v2"
        const val LEGACY_POLICY_VERSION = "title-author-year-weighted-edit-similarity-v1"
        const val AMBIGUITY_MARGIN = 0.02
        const val MAX_CANDIDATE_EVIDENCE = 3
        private val SUPPORTED_POLICY_VERSIONS = setOf(POLICY_VERSION, LEGACY_POLICY_VERSION)
        private const val TITLE_WEIGHT = 0.70
        private const val AUTHOR_WEIGHT = 0.20
        private const val YEAR_WEIGHT = 0.10
    }
}
