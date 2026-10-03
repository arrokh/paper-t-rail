package com.papertrail.api.citation.claims.service

import com.papertrail.api.analysis.configuration.AnalysisConfigurationSnapshot
import com.papertrail.api.analysis.configuration.ProviderSelection
import com.papertrail.api.citation.claims.domain.AnalyzedAtomicClaim
import com.papertrail.api.citation.claims.domain.ClaimAnalysisContextInput
import com.papertrail.api.citation.claims.domain.CitationContextClaims
import com.papertrail.api.citation.claims.provider.ClaimAnalysisProvider
import com.papertrail.api.citation.parsing.ParsedScientificDocument
import com.papertrail.api.infrastructure.messaging.NonRetryablePipelineException
import com.papertrail.api.infrastructure.providers.CLAIM_EXTRACTOR_ROLE
import com.papertrail.api.infrastructure.providers.ProviderCatalog
import com.papertrail.api.infrastructure.providers.ProviderRegistration
import org.springframework.stereotype.Service

@Service
class ClaimAnalysisService(
    private val providerCatalog: ProviderCatalog,
    providers: List<ClaimAnalysisProvider>,
    private val requestFactory: ClaimAnalysisRequestFactory,
) {
    private val providersById = providers.associateBy(ClaimAnalysisProvider::providerId).also { registered ->
        require(registered.size == providers.size) { "Claim-analysis provider IDs must be unique." }
    }

    fun analyze(
        parsed: ParsedScientificDocument,
        configuration: AnalysisConfigurationSnapshot,
    ): List<CitationContextClaims> {
        if (parsed.citationContexts.isEmpty()) return emptyList()
        return try {
            analyzeSelectedProvider(parsed, configuration)
        } catch (exception: NonRetryablePipelineException) {
            throw exception
        } catch (exception: IllegalArgumentException) {
            throw NonRetryablePipelineException(exception.message ?: "Claim analysis returned an invalid result.")
        }
    }

    private fun analyzeSelectedProvider(
        parsed: ParsedScientificDocument,
        configuration: AnalysisConfigurationSnapshot,
    ): List<CitationContextClaims> {
        val selection = configuration.claimExtractor
        val registration = providerCatalog.requireSelectable(CLAIM_EXTRACTOR_ROLE, selection.provider)
        validatePinnedProvider(selection, registration)
        val provider = providersById[selection.provider]
            ?: throw IllegalArgumentException("The selected claim-analysis provider is not available in this deployment.")
        validateProviderMetadata(provider, selection, registration)

        val request = requestFactory.from(parsed)
        if (request.contexts.isEmpty()) return emptyList()

        val output = provider.analyze(request, configuration)
        return validateOutput(request.contexts, output)
    }

    private fun validatePinnedProvider(selection: ProviderSelection, registration: ProviderRegistration) {
        val legacyHeuristicSnapshot = selection.provider == "heuristic" && selection.version == "v1" &&
            selection.targetSelectionPolicyVersion == null && selection.promptVersion == null && selection.outputMappingVersion == null
        if (selection.version != registration.version || selection.model != registration.model ||
            selection.trustBoundary != registration.trustBoundary.id ||
            selection.dataCategories.toSet() != registration.dataCategories.map { it.id }.toSet() ||
            selection.configurationFingerprint != registration.configurationFingerprint ||
            selection.retentionDisclosure != registration.retentionDisclosure ||
            (!legacyHeuristicSnapshot && (
                selection.targetSelectionPolicyVersion != registration.targetSelectionPolicyVersion ||
                    selection.promptVersion != registration.promptVersion ||
                    selection.outputMappingVersion != registration.outputMappingVersion
                ))
        ) {
            throw IllegalArgumentException("Claim-analysis provider configuration changed after this Analysis Run was created.")
        }
    }

    private fun validateProviderMetadata(
        provider: ClaimAnalysisProvider,
        selection: ProviderSelection,
        registration: ProviderRegistration,
    ) {
        if (provider.version != selection.version || provider.modelId != selection.model ||
            provider.targetSelectionPolicyVersion != registration.targetSelectionPolicyVersion ||
            provider.promptVersion != registration.promptVersion ||
            provider.outputMappingVersion != registration.outputMappingVersion
        ) {
            throw IllegalArgumentException("Claim-analysis adapter metadata does not match the pinned provider configuration.")
        }
    }

    private fun validateOutput(
        expectedContexts: List<ClaimAnalysisContextInput>,
        output: List<CitationContextClaims>,
    ): List<CitationContextClaims> {
        val expectedBySpan = expectedContexts.associateBy { it.contextStartOffset to it.contextEndOffset }
        require(expectedBySpan.size == expectedContexts.size) { "GROBID produced Citation Contexts with duplicate source spans." }
        val outputBySpan = output.groupBy { it.contextStartOffset to it.contextEndOffset }
        require(outputBySpan.size == output.size) { "Claim analysis returned a Citation Context more than once." }
        require(outputBySpan.keys == expectedBySpan.keys) { "Claim analysis did not return exactly the requested Citation Contexts." }

        return expectedContexts.map { expected ->
            val key = expected.contextStartOffset to expected.contextEndOffset
            val actual = outputBySpan.getValue(key).single()
            actual.copy(claims = validateClaims(expected, actual.claims))
        }
    }

    private fun validateClaims(
        context: ClaimAnalysisContextInput,
        claims: List<AnalyzedAtomicClaim>,
    ): List<AnalyzedAtomicClaim> {
        val targetOrder = context.targetCandidates.map { it.key }
        val allowedTargets = targetOrder.toSet()
        val normalized = claims.map { analyzed ->
            val candidate = analyzed.candidate
            require(candidate.text.isNotBlank()) { "Claim analysis returned an empty Atomic Claim." }
            require(candidate.sourceStartOffset >= context.contextStartOffset &&
                candidate.sourceEndOffset <= context.contextEndOffset &&
                candidate.sourceStartOffset < candidate.sourceEndOffset
            ) { "Claim analysis returned an Atomic Claim span outside its Citation Context." }
            require(analyzed.citationTargetKeys.distinct().size == analyzed.citationTargetKeys.size) {
                "Claim analysis returned duplicate Citation Target selections for an Atomic Claim."
            }
            require(analyzed.citationTargetKeys.all { it in allowedTargets }) {
                "Claim analysis selected a Citation Target outside its Citation Context."
            }
            val selected = analyzed.citationTargetKeys.toSet()
            analyzed.copy(citationTargetKeys = targetOrder.filter(selected::contains))
        }
        val bySpan = normalized.groupBy { it.candidate.sourceStartOffset to it.candidate.sourceEndOffset }
        val deduplicated = bySpan.map { (_, sameSpan) ->
            val distinctClaims = sameSpan.distinctBy { it.candidate.text to it.citationTargetKeys }
            require(distinctClaims.size == 1) { "Claim analysis returned conflicting Atomic Claims for the same source span." }
            distinctClaims.single()
        }
        return deduplicated.sortedWith(
            compareBy<AnalyzedAtomicClaim> { it.candidate.sourceStartOffset }
                .thenBy { it.candidate.sourceEndOffset }
                .thenBy { it.candidate.text },
        )
    }
}
