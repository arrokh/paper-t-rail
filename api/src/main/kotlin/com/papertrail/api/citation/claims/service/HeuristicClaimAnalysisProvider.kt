package com.papertrail.api.citation.claims.service

import com.papertrail.api.analysis.configuration.AnalysisConfigurationSnapshot
import com.papertrail.api.citation.claims.domain.AnalyzedAtomicClaim
import com.papertrail.api.citation.claims.domain.ClaimAnalysisRequest
import com.papertrail.api.citation.claims.domain.ClaimAnalysisVersions
import com.papertrail.api.citation.claims.domain.CitationContextClaims
import com.papertrail.api.citation.claims.domain.ClaimExtractionRequest
import com.papertrail.api.citation.claims.provider.ClaimAnalysisProvider
import com.papertrail.api.citation.parsing.ParsedCitationOccurrence
import org.springframework.stereotype.Component

@Component
class HeuristicClaimAnalysisProvider(
    private val extractor: HeuristicClaimExtractor,
) : ClaimAnalysisProvider {
    override val providerId: String get() = extractor.providerId
    override val version: String get() = extractor.version
    override val modelId: String? = null
    override val targetSelectionPolicyVersion: String = ClaimAnalysisVersions.HEURISTIC_TARGET_SELECTION_POLICY
    override val promptVersion: String? = null
    override val outputMappingVersion: String = ClaimAnalysisVersions.HEURISTIC_OUTPUT_MAPPING

    override fun analyze(
        request: ClaimAnalysisRequest,
        configuration: AnalysisConfigurationSnapshot,
    ): List<CitationContextClaims> {
        require(configuration.claimExtractor.provider == providerId) {
            "The heuristic claim analyzer was not selected for this Analysis Run."
        }
        return request.contexts.map { context ->
            val candidates = extractor.extract(
                ClaimExtractionRequest(
                    contextText = context.contextText,
                    contextStartOffset = context.contextStartOffset,
                    occurrences = context.occurrences.map { occurrence ->
                        ParsedCitationOccurrence(
                            markerText = occurrence.markerText,
                            startOffset = occurrence.startOffset,
                            endOffset = occurrence.endOffset,
                            bibliographyReferenceKeys = occurrence.targets.map { it.key.bibliographyReferenceKey },
                        )
                    },
                ),
            )
            val allTargetKeys = context.targetCandidates.map { it.key }
            CitationContextClaims(
                contextStartOffset = context.contextStartOffset,
                contextEndOffset = context.contextEndOffset,
                claims = candidates.map { candidate -> AnalyzedAtomicClaim(candidate, allTargetKeys) },
            )
        }
    }
}
