package com.papertrail.api.scholarly.references.resolver

import com.papertrail.api.scholarly.references.client.BibliographyReference
import com.papertrail.api.scholarly.references.client.ScholarlyMetadataLookup
import com.papertrail.api.scholarly.references.normalization.DoiNormalizer

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
            "BOOK",
        )
    }
}
