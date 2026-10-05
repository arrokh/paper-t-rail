package com.papertrail.api.citation.claims.service

import com.papertrail.api.analysis.configuration.AnalysisConfigurationSnapshot
import com.papertrail.api.analysis.configuration.ProviderSelection
import com.papertrail.api.analysis.configuration.ValidationLimitsSnapshot
import com.papertrail.api.citation.claims.domain.AnalyzedAtomicClaim
import com.papertrail.api.citation.claims.domain.AtomicClaimCandidate
import com.papertrail.api.citation.claims.domain.CitationContextClaims
import com.papertrail.api.citation.claims.domain.CitationTargetKey
import com.papertrail.api.citation.claims.domain.ClaimAnalysisContextInput
import com.papertrail.api.citation.claims.domain.ClaimAnalysisRequest
import com.papertrail.api.citation.claims.provider.ClaimAnalysisProvider
import com.papertrail.api.citation.parsing.ParsedBibliographyEntry
import com.papertrail.api.citation.parsing.ParsedCitationContext
import com.papertrail.api.citation.parsing.ParsedCitationOccurrence
import com.papertrail.api.citation.parsing.ParsedScientificDocument
import com.papertrail.api.citation.parsing.ParsedSection
import com.papertrail.api.infrastructure.providers.CLAIM_EXTRACTOR_ROLE
import com.papertrail.api.infrastructure.providers.DataCategory
import com.papertrail.api.infrastructure.providers.ProviderCatalog
import com.papertrail.api.infrastructure.providers.ProviderRegistration
import com.papertrail.api.infrastructure.providers.ProviderTrustBoundary
import org.junit.jupiter.api.Assertions.assertEquals
import ch.qos.logback.classic.Logger
import ch.qos.logback.core.read.ListAppender
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory

class ClaimAnalysisServiceTest {
    @Test
    fun `heuristic analysis keeps every context target and returns source-spanned claims`() {
        val document = documentWithOneContextAndTwoTargets()
        val provider = HeuristicClaimAnalysisProvider(HeuristicClaimExtractor())
        val service = serviceFor(provider)

        val result = service.analyze(document, configurationFor(provider))

        val claim = result.single().claims.single()
        assertEquals("Treatment reduced pain", claim.candidate.text)
        assertEquals(0, claim.candidate.sourceStartOffset)
        assertEquals(document.citationContexts.single().text.indexOf(" ["), claim.candidate.sourceEndOffset)
        assertEquals(
            listOf(
                CitationTargetKey(0, "ref1"),
                CitationTargetKey(0, "ref2"),
            ),
            claim.citationTargetKeys,
        )
        assertEquals(2L, ClaimCitationPairCounter.count(document, result))
    }

    @Test
    fun `accepts a selected subset and an empty target selection without inventing links`() {
        val document = documentWithTwoContexts()
        val provider = FixedClaimAnalysisProvider { request ->
            listOf(
                CitationContextClaims(
                    request.contexts[0].contextStartOffset,
                    request.contexts[0].contextEndOffset,
                    listOf(
                        AnalyzedAtomicClaim(
                            AtomicClaimCandidate("First claim", 0, 11),
                            listOf(CitationTargetKey(0, "ref1")),
                        ),
                    ),
                ),
                CitationContextClaims(
                    request.contexts[1].contextStartOffset,
                    request.contexts[1].contextEndOffset,
                    listOf(
                        AnalyzedAtomicClaim(
                            AtomicClaimCandidate("Second claim", request.contexts[1].contextStartOffset, request.contexts[1].contextStartOffset + 12),
                            emptyList(),
                        ),
                    ),
                ),
            )
        }
        val service = serviceFor(provider)

        val result = service.analyze(document, configurationFor(provider))

        assertEquals(listOf(CitationTargetKey(0, "ref1")), result[0].claims.single().citationTargetKeys)
        assertTrue(result[1].claims.single().citationTargetKeys.isEmpty())
        assertEquals(1L, ClaimCitationPairCounter.count(document, result))
    }

    @Test
    fun `rejects a target key that belongs to another context`() {
        val document = documentWithTwoContexts()
        val provider = FixedClaimAnalysisProvider { request ->
            request.contexts.map { context ->
                CitationContextClaims(
                    context.contextStartOffset,
                    context.contextEndOffset,
                    listOf(
                        AnalyzedAtomicClaim(
                            AtomicClaimCandidate("Claim", context.contextStartOffset, context.contextStartOffset + 5),
                            listOf(CitationTargetKey(0, "ref1")),
                        ),
                    ),
                )
            }
        }

        val failure = assertThrows(IllegalArgumentException::class.java) {
            serviceFor(provider).analyze(document, configurationFor(provider))
        }

        assertTrue(failure.message.orEmpty().contains("Citation Context"))
    }

    @Test
    fun `rejects source spans outside their Citation Context`() {
        val document = documentWithTwoContexts()
        val provider = FixedClaimAnalysisProvider { request ->
            request.contexts.mapIndexed { index, context ->
                CitationContextClaims(
                    context.contextStartOffset,
                    context.contextEndOffset,
                    if (index == 0) listOf(AnalyzedAtomicClaim(AtomicClaimCandidate("Claim", -1, 4), emptyList())) else emptyList(),
                )
            }
        }

        val failure = assertThrows(IllegalArgumentException::class.java) {
            serviceFor(provider).analyze(document, configurationFor(provider))
        }

        assertTrue(failure.message.orEmpty().contains("outside its Citation Context"))
    }

    @Test
    fun `rejects conflicting claims sharing one source span and logs only safe counts`() {
        val document = documentWithOneContextAndTwoTargets()
        val privateClaimTexts = listOf("private claim one", "private claim two")
        val provider = FixedClaimAnalysisProvider { request ->
            val context = request.contexts.single()
            listOf(
                CitationContextClaims(
                    context.contextStartOffset,
                    context.contextEndOffset,
                    privateClaimTexts.map { text ->
                        AnalyzedAtomicClaim(
                            AtomicClaimCandidate(text, context.contextStartOffset, context.contextStartOffset + 5),
                            emptyList(),
                        )
                    },
                ),
            )
        }
        val logger = LoggerFactory.getLogger(ClaimAnalysisService::class.java) as Logger
        val appender = ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        try {
            val failure = assertThrows(IllegalArgumentException::class.java) {
                serviceFor(provider).analyze(document, configurationFor(provider))
            }

            assertTrue(failure.message.orEmpty().contains("conflicting Atomic Claims"))
            val event = appender.list.single { it.message == "Claim analysis returned conflicting claims for identical source spans" }
            val fields = event.keyValuePairs.associate { it.key to it.value }
            assertEquals(1, fields["conflictingSpanGroupCount"])
            assertEquals(2, fields["conflictingClaimCount"])
            assertEquals(2, fields["distinctClaimTextCount"])
            assertEquals(1, fields["distinctTargetSelectionCount"])
            val loggedEvents = appender.list.joinToString(" ") { it.formattedMessage + it.keyValuePairs }
            privateClaimTexts.forEach { assertFalse(loggedEvents.contains(it)) }
            assertFalse(loggedEvents.contains("sourceStartOffset"))
        } finally {
            logger.detachAppender(appender)
            appender.stop()
        }
    }

    @Test
    fun `does not fall back to the heuristic when the explicitly selected provider fails`() {
        var heuristicInvoked = false
        val selected = FixedClaimAnalysisProvider { throw IllegalStateException("Selected provider failed.") }
        val heuristic = object : ClaimAnalysisProvider {
            override val providerId = "heuristic"
            override val version = "v1"
            override val modelId: String? = null
            override val targetSelectionPolicyVersion = "heuristic-all-context-targets-v1"
            override val promptVersion: String? = null
            override val outputMappingVersion = "heuristic-claim-analysis-v1"

            override fun analyze(
                request: ClaimAnalysisRequest,
                configuration: AnalysisConfigurationSnapshot,
            ): List<CitationContextClaims> {
                heuristicInvoked = true
                return emptyList()
            }
        }

        val failure = assertThrows(IllegalStateException::class.java) {
            serviceFor(selected, listOf(selected, heuristic)).analyze(
                documentWithOneContextAndTwoTargets(),
                configurationFor(selected),
            )
        }

        assertEquals("Selected provider failed.", failure.message)
        assertFalse(heuristicInvoked)
    }

    @Test
    fun `does not invoke a provider when GROBID produced no Citation Contexts`() {
        var invoked = false
        val provider = FixedClaimAnalysisProvider {
            invoked = true
            emptyList()
        }
        val service = serviceFor(provider)

        val result = service.analyze(documentWithNoContexts(), configurationFor(provider))

        assertTrue(result.isEmpty())
        assertFalse(invoked)
    }

    private fun serviceFor(
        provider: ClaimAnalysisProvider,
        providers: List<ClaimAnalysisProvider> = listOf(provider),
    ): ClaimAnalysisService = ClaimAnalysisService(
        providerCatalog = catalogFor(provider),
        providers = providers,
        requestFactory = ClaimAnalysisRequestFactory(),
    )

    private fun configurationFor(provider: ClaimAnalysisProvider): AnalysisConfigurationSnapshot =
        AnalysisConfigurationSnapshot(
            claimExtractor = ProviderSelection(
                provider = provider.providerId,
                version = provider.version,
                model = provider.modelId,
                trustBoundary = ProviderTrustBoundary.LOCAL.id,
                dataCategories = listOf(DataCategory.CITATION_CONTEXT.id),
                configurationFingerprint = "fixture-endpoint-fingerprint",
                targetSelectionPolicyVersion = provider.targetSelectionPolicyVersion,
                promptVersion = provider.promptVersion,
                outputMappingVersion = provider.outputMappingVersion,
            ),
            embedding = ProviderSelection("local", "v1"),
            systemOne = ProviderSelection("mock", "v1"),
            sourceParser = ProviderSelection("grobid", "0.9.1-crf"),
            languageDetector = ProviderSelection("optimaize", "0.6"),
            validationLimits = ValidationLimitsSnapshot(1_000_000, 20, 100_000, 100_000, 100, 0.65),
            externalProviderConsents = emptyList(),
        )

    private fun catalogFor(provider: ClaimAnalysisProvider): ProviderCatalog = ProviderCatalog(
        listOf(
            ProviderRegistration(
                role = CLAIM_EXTRACTOR_ROLE,
                providerId = provider.providerId,
                displayName = "Fixture claim analyzer",
                version = provider.version,
                model = provider.modelId,
                trustBoundary = ProviderTrustBoundary.LOCAL,
                enabled = true,
                dataCategories = setOf(DataCategory.CITATION_CONTEXT),
                configurationFingerprint = "fixture-endpoint-fingerprint",
                targetSelectionPolicyVersion = provider.targetSelectionPolicyVersion,
                promptVersion = provider.promptVersion,
                outputMappingVersion = provider.outputMappingVersion,
            ),
        ),
    )

    private fun documentWithOneContextAndTwoTargets(): ParsedScientificDocument {
        val text = "Treatment reduced pain [1, 2]."
        val markerStart = text.indexOf("[1, 2]")
        return ParsedScientificDocument(
            parserId = "grobid",
            parserVersion = "0.9.1-crf",
            normalizedSourceText = text,
            sections = listOf(ParsedSection(0, "Results", text, 0, text.length)),
            citationContexts = listOf(
                ParsedCitationContext(
                    sectionOrder = 0,
                    boundaryKind = "SENTENCE_FALLBACK",
                    text = text,
                    startOffset = 0,
                    endOffset = text.length,
                    occurrences = listOf(ParsedCitationOccurrence("[1, 2]", markerStart, markerStart + 6, listOf("ref1", "ref2"))),
                ),
            ),
            bibliographyEntries = bibliographyEntries(),
        )
    }

    private fun documentWithTwoContexts(): ParsedScientificDocument {
        val text = "First claim [1]. Second claim [2]."
        val firstMarker = text.indexOf("[1]")
        val secondContextStart = text.indexOf("Second claim")
        val secondMarker = text.indexOf("[2]")
        return ParsedScientificDocument(
            parserId = "grobid",
            parserVersion = "0.9.1-crf",
            normalizedSourceText = text,
            sections = listOf(ParsedSection(0, "Results", text, 0, text.length)),
            citationContexts = listOf(
                ParsedCitationContext(
                    0, "CLAUSE", text.substring(0, secondContextStart - 1), 0, secondContextStart - 1,
                    listOf(ParsedCitationOccurrence("[1]", firstMarker, firstMarker + 3, listOf("ref1"))),
                ),
                ParsedCitationContext(
                    0, "CLAUSE", text.substring(secondContextStart), secondContextStart, text.length,
                    listOf(ParsedCitationOccurrence("[2]", secondMarker, secondMarker + 3, listOf("ref2"))),
                ),
            ),
            bibliographyEntries = bibliographyEntries(),
        )
    }

    private fun documentWithNoContexts(): ParsedScientificDocument = ParsedScientificDocument(
        parserId = "grobid",
        parserVersion = "0.9.1-crf",
        normalizedSourceText = "No citation-bearing claims.",
        sections = listOf(ParsedSection(0, "Results", "No citation-bearing claims.", 0, 27)),
        citationContexts = emptyList(),
        bibliographyEntries = emptyList(),
    )

    private fun bibliographyEntries() = listOf(
        ParsedBibliographyEntry(0, "ref1", "Reference one", "Reference one", listOf("Ada Example"), 2024, "10.1000/one", "JOURNAL_ARTICLE"),
        ParsedBibliographyEntry(1, "ref2", "Reference two", "Reference two", listOf("Grace Example"), 2023, "10.1000/two", "JOURNAL_ARTICLE"),
    )

    private class FixedClaimAnalysisProvider(
        private val result: (ClaimAnalysisRequest) -> List<CitationContextClaims>,
    ) : ClaimAnalysisProvider {
        override val providerId = "fixture-analyzer"
        override val version = "v1"
        override val modelId: String? = "fixture-model"
        override val targetSelectionPolicyVersion = "model-selected-same-context-targets-v1"
        override val promptVersion = "fixture-prompt-v1"
        override val outputMappingVersion = "fixture-output-v1"

        override fun analyze(
            request: ClaimAnalysisRequest,
            configuration: AnalysisConfigurationSnapshot,
        ): List<CitationContextClaims> = result(request)
    }
}
