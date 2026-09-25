package com.papertrail.api.citation.claims.service

import com.papertrail.api.citation.claims.domain.AtomicClaimCandidate
import com.papertrail.api.citation.claims.domain.ClaimExtractionRequest
import com.papertrail.api.citation.claims.domain.CitationContextClaims
import com.papertrail.api.citation.claims.provider.ClaimExtractorProvider
import com.papertrail.api.citation.parsing.ParsedCitationContext
import com.papertrail.api.infrastructure.providers.CLAIM_EXTRACTOR_ROLE
import com.papertrail.api.infrastructure.providers.ProviderCatalog
import com.papertrail.api.infrastructure.providers.ProviderTrustBoundary
import org.springframework.stereotype.Component

/** Resolves enabled, local, version-pinned claim extractors and validates their source spans. */
@Component
class ClaimExtractionService(
    private val providerCatalog: ProviderCatalog,
    providers: List<ClaimExtractorProvider>,
) {
    private val providersById = providers.associateBy(ClaimExtractorProvider::providerId)

    init {
        require(providersById.size == providers.size) { "Claim extractor implementations must have unique provider IDs." }
    }

    fun extract(
        providerId: String,
        version: String,
        contexts: List<ParsedCitationContext>,
    ): List<CitationContextClaims> {
        val registration = providerCatalog.requireSelectable(CLAIM_EXTRACTOR_ROLE, providerId)
        require(registration.trustBoundary == ProviderTrustBoundary.LOCAL) {
            "Only local claim extractors are supported until an external provider-call gate is configured."
        }
        require(registration.version == version) {
            "The selected claim extractor configuration changed after this Analysis Run was created."
        }
        val provider = providersById[providerId]
            ?: throw IllegalStateException("No claim extractor implementation is available for the selected provider.")
        require(provider.version == version) {
            "The selected claim extractor implementation does not match this Analysis Run provenance."
        }

        return contexts.map { context ->
            val request = ClaimExtractionRequest(context.text, context.startOffset, context.occurrences)
            val candidates = provider.extract(request)
            val deduplicated = candidates
                .groupBy { it.sourceStartOffset to it.sourceEndOffset }
                .map { (_, sameSpanCandidates) ->
                    val distinctTexts = sameSpanCandidates.map(AtomicClaimCandidate::text).distinct()
                    require(distinctTexts.size == 1) {
                        "The claim extractor returned conflicting claims for one source span."
                    }
                    sameSpanCandidates.first()
                }
                .sortedWith(compareBy(AtomicClaimCandidate::sourceStartOffset, AtomicClaimCandidate::sourceEndOffset))

            deduplicated.forEach { candidate ->
                require(candidate.text.isNotBlank()) { "The claim extractor returned an empty Atomic Claim." }
                require(candidate.sourceStartOffset >= context.startOffset &&
                    candidate.sourceEndOffset <= context.endOffset &&
                    candidate.sourceStartOffset < candidate.sourceEndOffset
                ) { "The claim extractor returned a source span outside its Citation Context." }
            }
            CitationContextClaims(context.startOffset, context.endOffset, deduplicated)
        }
    }
}
