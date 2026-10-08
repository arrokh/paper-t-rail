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

        val suppliedDoi = !reference.doi.isNullOrBlank()
        val doi = DoiNormalizer.normalize(reference.doi)
        if (suppliedDoi && doi == null && matcher.policyVersion != ScholarlyMetadataMatcher.LEGACY_POLICY_VERSION) {
            return ReferenceResolutionDecision(ReferenceResolutionStatus.UNRESOLVED, "INVALID_IDENTIFIER")
        }
        if (doi != null) {
            val doiWork = scholarlyMetadata.byDoi(doi)
            if (matcher.policyVersion == ScholarlyMetadataMatcher.LEGACY_POLICY_VERSION) {
                if (doiWork != null && DoiNormalizer.normalize(doiWork.doi) == doi) {
                    return ReferenceResolutionDecision(
                        ReferenceResolutionStatus.RESOLVED,
                        "DOI_CONFIRMED",
                        doiWork,
                        1.0,
                        "CONFIRMED_DOI",
                    )
                }
            } else {
                if (doiWork == null) {
                    return ReferenceResolutionDecision(ReferenceResolutionStatus.UNRESOLVED, "DOI_NOT_FOUND")
                }
                if (DoiNormalizer.normalize(doiWork.doi) != doi) {
                    return ReferenceResolutionDecision(
                        ReferenceResolutionStatus.UNRESOLVED,
                        "DOI_CONFLICT",
                        candidateEvidence = matcher.identifierCandidateEvidence(reference, doiWork, "DOI_CONFLICT"),
                    )
                }
                val metadataConflict = matcher.identifierConflictReason(reference, doiWork)
                if (metadataConflict != null) {
                    return ReferenceResolutionDecision(
                        ReferenceResolutionStatus.UNRESOLVED,
                        metadataConflict,
                        candidateEvidence = matcher.identifierCandidateEvidence(reference, doiWork, metadataConflict),
                    )
                }
                return ReferenceResolutionDecision(
                    status = ReferenceResolutionStatus.RESOLVED,
                    reasonCode = "DOI_CONFIRMED",
                    work = doiWork,
                    score = 1.0,
                    matchMethod = "CONFIRMED_DOI",
                    candidateEvidence = matcher.identifierCandidateEvidence(reference, doiWork, "DOI_CONFIRMED"),
                )
            }
        }

        if (reference.title.isNullOrBlank()) {
            return ReferenceResolutionDecision(
                ReferenceResolutionStatus.UNRESOLVED,
                "INSUFFICIENT_MATCH_METADATA",
            )
        }
        if (matcher.policyVersion == ScholarlyMetadataMatcher.POLICY_VERSION) {
            matcher.insufficientMetadataReason(reference)?.let { reason ->
                return ReferenceResolutionDecision(ReferenceResolutionStatus.UNRESOLVED, reason)
            }
        }

        val match = matcher.match(reference, scholarlyMetadata.search(reference))
        return if (match.candidate != null) {
            ReferenceResolutionDecision(
                ReferenceResolutionStatus.RESOLVED,
                "METADATA_MATCH",
                match.candidate,
                match.score,
                "METADATA_MATCH",
                candidateEvidence = match.candidateEvidence,
            )
        } else {
            ReferenceResolutionDecision(
                ReferenceResolutionStatus.UNRESOLVED,
                match.reasonCode,
                score = match.score,
                matchMethod = "METADATA_MATCH",
                candidateEvidence = match.candidateEvidence,
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
