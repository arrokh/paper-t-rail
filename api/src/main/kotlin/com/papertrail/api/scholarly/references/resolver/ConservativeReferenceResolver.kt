package com.papertrail.api.scholarly.references.resolver

import com.papertrail.api.scholarly.references.client.BibliographyReference
import com.papertrail.api.scholarly.references.client.ScholarlyMetadataLookup
import com.papertrail.api.scholarly.references.normalization.DoiNormalizer

class ConservativeReferenceResolver(
    private val scholarlyMetadata: ScholarlyMetadataLookup,
    private val matcher: ScholarlyMetadataMatcher,
) {
    fun preflight(reference: BibliographyReference): ReferenceResolutionDecision? = prepare(reference).decision

    fun resolve(reference: BibliographyReference): ReferenceResolutionDecision {
        val prepared = prepare(reference)
        prepared.decision?.let { return it }
        val isLegacyPolicy = matcher.policyVersion == ScholarlyMetadataMatcher.LEGACY_POLICY_VERSION
        val doi = prepared.doi
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

        val match = matcher.match(reference, scholarlyMetadata.search(reference.copy(doiIdentifiers = emptyList())))
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

    private fun prepare(reference: BibliographyReference): PreparedReference {
        if (reference.referenceType !in SUPPORTED_REFERENCE_TYPES) {
            return PreparedReference(
                ReferenceResolutionDecision(
                    ReferenceResolutionStatus.UNSUPPORTED_REFERENCE_TYPE,
                    "UNSUPPORTED_REFERENCE_TYPE",
                ),
                null,
            )
        }

        val isLegacyPolicy = matcher.policyVersion == ScholarlyMetadataMatcher.LEGACY_POLICY_VERSION
        val suppliedDois = if (isLegacyPolicy) {
            listOfNotNull(reference.doi?.takeIf(String::isNotBlank))
        } else {
            (reference.doiIdentifiers + listOfNotNull(reference.doi)).filter(String::isNotBlank)
        }
        val normalizedDois = suppliedDois.map(DoiNormalizer::normalize)
        if (!isLegacyPolicy && normalizedDois.any { it == null }) {
            return PreparedReference(ReferenceResolutionDecision(ReferenceResolutionStatus.UNRESOLVED, "INVALID_IDENTIFIER"), null)
        }
        val distinctDois = normalizedDois.filterNotNull().toSet()
        if (!isLegacyPolicy && distinctDois.size > 1) {
            return PreparedReference(ReferenceResolutionDecision(ReferenceResolutionStatus.UNRESOLVED, "CONFLICTING_DOI_IDENTIFIERS"), null)
        }
        val doi = if (isLegacyPolicy) DoiNormalizer.normalize(reference.doi) else distinctDois.singleOrNull()
        if (doi == null) {
            if (reference.title.isNullOrBlank()) {
                return PreparedReference(
                    ReferenceResolutionDecision(ReferenceResolutionStatus.UNRESOLVED, "INSUFFICIENT_MATCH_METADATA"),
                    null,
                )
            }
            if (matcher.policyVersion == ScholarlyMetadataMatcher.POLICY_VERSION) {
                matcher.insufficientMetadataReason(reference)?.let { reason ->
                    return PreparedReference(ReferenceResolutionDecision(ReferenceResolutionStatus.UNRESOLVED, reason), null)
                }
            }
        }
        return PreparedReference(null, doi)
    }

    private data class PreparedReference(
        val decision: ReferenceResolutionDecision?,
        val doi: String?,
    )

    companion object {
        val SUPPORTED_REFERENCE_TYPES = setOf(
            "JOURNAL_ARTICLE",
            "CONFERENCE_PAPER",
            "PREPRINT",
            "ACADEMIC_MANUSCRIPT",
            "BOOK",
            "BOOK_CHAPTER",
            "INBOOK",
            "INCOLLECTION",
        )
    }
}
