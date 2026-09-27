package com.papertrail.api.citation.claims.service

import com.papertrail.api.citation.claims.domain.CitationContextClaims
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
            val claimCount = claimsByContext.getValue(context.startOffset to context.endOffset).claims.size.toLong()
            val targetCount = context.occurrences.sumOf { occurrence ->
                occurrence.bibliographyReferenceKeys.distinct().count(bibliographyKeys::contains)
            }.toLong()
            Math.addExact(total, Math.multiplyExact(claimCount, targetCount))
        }
    }
}
