package com.papertrail.api.citation.claims.service

import com.papertrail.api.citation.claims.domain.CitationContextClaims
import com.papertrail.api.citation.claims.domain.CitationTargetKey
import com.papertrail.api.citation.parsing.ParsedScientificDocument

object ClaimCitationPairCounter {
    fun count(
        parsed: ParsedScientificDocument,
        extractedClaims: List<CitationContextClaims>,
    ): Long {
        val claimsByContext = extractedClaims.associateBy { it.contextStartOffset to it.contextEndOffset }
        val contextSpans = parsed.citationContexts.map { it.startOffset to it.endOffset }.toSet()
        require(claimsByContext.size == extractedClaims.size && claimsByContext.keys == contextSpans) {
            "Extracted Atomic Claims must match every Citation Context exactly once."
        }

        val bibliographyKeys = parsed.bibliographyEntries.mapTo(mutableSetOf()) { it.localReferenceKey }
        return parsed.citationContexts.fold(0L) { total, context ->
            val claims = claimsByContext.getValue(context.startOffset to context.endOffset).claims
            val targetKeys = context.occurrences.flatMapIndexed { occurrenceOrdinal, occurrence ->
                occurrence.bibliographyReferenceKeys.distinct().map { referenceKey ->
                    require(referenceKey in bibliographyKeys) { "A Citation Target refers to a missing Bibliography Entry." }
                    CitationTargetKey(occurrenceOrdinal, referenceKey)
                }
            }.toSet()
            val selectedTargetCount = claims.fold(0L) { contextTotal, claim ->
                require(claim.citationTargetKeys.distinct().size == claim.citationTargetKeys.size &&
                    claim.citationTargetKeys.all(targetKeys::contains)
                ) { "Atomic Claim links must select unique Citation Targets from their own Citation Context." }
                Math.addExact(contextTotal, claim.citationTargetKeys.size.toLong())
            }
            Math.addExact(total, selectedTargetCount)
        }
    }
}
