package com.papertrail.api.scholarly.references.identity

import com.papertrail.api.citation.parsing.BibliographyNormalizationPolicySelection
import com.papertrail.api.citation.parsing.ParsedBibliographicMetadataCandidate
import com.papertrail.api.citation.parsing.ParsedBibliographicMetadataField
import com.papertrail.api.scholarly.references.client.BibliographyReference
import java.util.Locale

object CitedWorkIdentityPolicy {
    const val VERSION = "recovery-upload-identity-v2"

    fun evaluate(
        referenceType: String,
        bibliographyPolicy: BibliographyNormalizationPolicySelection?,
        reference: BibliographyReference?,
        candidates: List<ParsedBibliographicMetadataCandidate>,
    ): CitedWorkIdentityDecision {
        val normalizedReferenceType = referenceType.uppercase(Locale.ROOT)
        val chapterReference = normalizedReferenceType in CHAPTER_REFERENCE_TYPES
        val legacyBookReference = normalizedReferenceType == "BOOK" && bibliographyPolicy?.supportsChapterTypeClassification() != true
        fun inconclusive(reason: String) = when {
            chapterReference -> CitedWorkIdentityDecision(CitedWorkIdentityOutcome.MISMATCH, "CHAPTER_IDENTITY_UNSUPPORTED")
            legacyBookReference -> CitedWorkIdentityDecision(CitedWorkIdentityOutcome.MISMATCH, "BOOK_CHAPTER_IDENTITY_UNSUPPORTED")
            else -> CitedWorkIdentityDecision(CitedWorkIdentityOutcome.NEEDS_CONFIRMATION, reason)
        }

        val resolvedReference = reference ?: return inconclusive("REFERENCE_IDENTITY_UNRESOLVED")
        val candidateDois = candidates
            .filter { it.field == ParsedBibliographicMetadataField.DOI }
            .mapNotNull { normalizeDoi(it.value) }
            .distinct()
        if (candidateDois.isEmpty()) return inconclusive("DOI_NOT_EXTRACTED")
        if (candidateDois.size > 1) return inconclusive("MULTIPLE_DOI_CANDIDATES")
        val expectedDoi = resolvedReference.doi?.let(::normalizeDoi)
            ?: return inconclusive("REFERENCE_DOI_UNAVAILABLE")
        val expectedTitle = normalizeTitle(resolvedReference.title)
        val candidateTitles = candidates
            .filter { it.field == ParsedBibliographicMetadataField.TITLE }
            .mapNotNull { normalizeTitle(it.value) }
            .distinct()
        if (candidateTitles.size > 1) return inconclusive("MULTIPLE_TITLE_CANDIDATES")
        val candidateTitle = candidateTitles.singleOrNull()
        val titleConflicts = expectedTitle != null && candidateTitle != null && candidateTitle != expectedTitle
        if (titleConflicts) {
            return CitedWorkIdentityDecision(CitedWorkIdentityOutcome.MISMATCH, "DOI_TITLE_CONFLICT")
        }

        if (candidateDois.single() != expectedDoi) {
            if (expectedTitle != null && candidateTitle == expectedTitle) {
                return CitedWorkIdentityDecision(CitedWorkIdentityOutcome.VALIDATED, "TITLE_MATCH_DIFFERENT_DOI")
            }
            return inconclusive("DOI_DIFFERS_REQUIRES_CONFIRMATION")
        }
        return CitedWorkIdentityDecision(CitedWorkIdentityOutcome.VALIDATED, "DOI_MATCH")
    }

    private fun normalizeDoi(value: String): String? = value
        .trim()
        .lowercase(Locale.ROOT)
        .replace(Regex("^https?://(?:dx\\.)?doi\\.org/"), "")
        .replace(Regex("^doi:\\s*"), "")
        .takeIf(String::isNotBlank)

    private fun normalizeTitle(value: String?): String? = value
        ?.lowercase(Locale.ROOT)
        ?.replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        ?.trim()
        ?.replace(Regex("\\s+"), " ")
        ?.takeIf(String::isNotBlank)

    private val CHAPTER_REFERENCE_TYPES = setOf("BOOK_CHAPTER", "INBOOK", "INCOLLECTION")
}
