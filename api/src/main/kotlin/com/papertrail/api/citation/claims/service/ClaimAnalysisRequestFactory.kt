package com.papertrail.api.citation.claims.service

import com.papertrail.api.citation.claims.domain.ClaimAnalysisContextInput
import com.papertrail.api.citation.claims.domain.ClaimAnalysisOccurrenceInput
import com.papertrail.api.citation.claims.domain.ClaimAnalysisRequest
import com.papertrail.api.citation.claims.domain.ClaimAnalysisTargetCandidate
import com.papertrail.api.citation.claims.domain.CitationTargetKey
import com.papertrail.api.citation.parsing.ParsedScientificDocument
import org.springframework.stereotype.Component

@Component
class ClaimAnalysisRequestFactory {
    fun from(parsed: ParsedScientificDocument): ClaimAnalysisRequest {
        val bibliographyByKey = parsed.bibliographyEntries.associateBy { it.localReferenceKey }
        require(bibliographyByKey.size == parsed.bibliographyEntries.size) {
            "The parsed document contains duplicate bibliography keys."
        }
        return ClaimAnalysisRequest(
            contexts = parsed.citationContexts.map { context ->
                ClaimAnalysisContextInput(
                    contextText = context.text,
                    contextStartOffset = context.startOffset,
                    contextEndOffset = context.endOffset,
                    occurrences = context.occurrences.mapIndexed { occurrenceOrdinal, occurrence ->
                        ClaimAnalysisOccurrenceInput(
                            ordinal = occurrenceOrdinal,
                            markerText = occurrence.markerText,
                            startOffset = occurrence.startOffset,
                            endOffset = occurrence.endOffset,
                            targets = occurrence.bibliographyReferenceKeys.distinct().map { referenceKey ->
                                val bibliography = bibliographyByKey[referenceKey]
                                    ?: throw IllegalArgumentException("A Citation Target refers to a missing Bibliography Entry.")
                                ClaimAnalysisTargetCandidate(
                                    key = CitationTargetKey(occurrenceOrdinal, referenceKey),
                                    title = bibliography.title,
                                    authors = bibliography.authors,
                                    year = bibliography.year,
                                    doi = bibliography.doi,
                                )
                            },
                        )
                    },
                )
            },
        )
    }
}
