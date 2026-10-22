package com.papertrail.api.analysis.report

import com.fasterxml.jackson.databind.ObjectMapper
import com.papertrail.api.analysis.http.AnalysisRunSummary
import com.papertrail.api.analysis.http.ParsedAtomicClaimView
import com.papertrail.api.analysis.http.ParsedBibliographyEntryView
import com.papertrail.api.analysis.http.ParsedCitationContextView
import com.papertrail.api.analysis.http.ParsedClaimCitationTargetView
import com.papertrail.api.analysis.http.ParsedDocumentView
import com.papertrail.api.analysis.http.ParsedParserProvenance
import com.papertrail.api.analysis.report.http.AnalysisRunComparisonResponse
import com.papertrail.api.analysis.report.http.AnalysisRunComparisonResponse.EvidencePassage
import com.papertrail.api.analysis.report.http.AnalysisRunComparisonResponse.PairResult
import com.papertrail.api.analysis.report.service.AnalysisRunComparisonAlignment
import com.papertrail.api.analysis.report.service.AnalysisRunComparisonService
import com.papertrail.api.analysis.report.service.ComparisonCandidate
import com.papertrail.api.citation.parsing.ParsedBibliographyIdentifier
import com.papertrail.api.analysis.report.service.AnalysisRunReportService
import com.papertrail.api.analysis.service.AnalysisRunService
import com.papertrail.api.citation.parsing.BibliographyNormalizationPolicySelection
import com.papertrail.api.evidence.report.CitedReferenceVerificationOutcome
import com.papertrail.api.evidence.report.EvidenceCoverageReport
import com.papertrail.api.evidence.report.EvidenceCoverageSummary
import com.papertrail.api.review.domain.HumanReview
import com.papertrail.api.review.domain.HumanReviewAction
import com.papertrail.api.scholarly.references.report.BibliographyResolutionReportEntry
import com.papertrail.api.scholarly.references.report.ReferenceResolutionReport
import com.papertrail.api.scholarly.references.report.ReferenceResolutionSummary
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.time.Instant
import java.util.UUID

class AnalysisRunComparisonServiceTest {
    private val mapper = ObjectMapper()

    @Test
    fun `same-source runs align by source span and reference identity, keep incomplete state separate, and leave Human Reviews attached to their run`() {
        val leftRunId = UUID.randomUUID()
        val review = HumanReview(
            id = UUID.randomUUID(),
            analysisRunId = leftRunId,
            verificationId = UUID.randomUUID(),
            action = HumanReviewAction.DISAGREE,
            overrideStatus = null,
            note = "Review is specific to the left run.",
            createdAt = Instant.parse("2026-10-01T00:01:00Z"),
        )
        val left = testRun(
            hash = "a".repeat(64),
            configVersion = "extractor-v1",
            claims = listOf(ClaimSpec("The finding is reported.", 10, 33, "reference-a", "The finding is reported [1].")),
            references = listOf(ReferenceSpec("reference-a", "Example study", "10.1234/example")),
            outcomes = listOf(OutcomeSpec(0, processingStatus = "COMPLETED", finalStatus = "SUPPORTED", reviews = listOf(review))),
            runId = leftRunId,
        )
        val right = testRun(
            hash = "a".repeat(64),
            configVersion = "extractor-v2",
            claims = listOf(ClaimSpec("The finding is reported.", 10, 33, "reference-b", "The finding is reported [1].")),
            references = listOf(ReferenceSpec("reference-b", "Example study", "10.1234/example")),
            outcomes = listOf(OutcomeSpec(0, processingStatus = "INCOMPLETE", finalStatus = "SUPPORTED", failureReason = "SYSTEM_ONE_TIMEOUT")),
        )

        val comparison = compare(left, right)

        assertFalse(comparison.sourceContentDiffers)
        assertTrue(comparison.analysisConfigurationDiffers)
        assertEquals(1, comparison.counts.changed)
        assertEquals(1, comparison.counts.outcomeChanges)
        val item = comparison.items.single()
        assertEquals("CHANGED", item.changeKind)
        assertEquals("machineOutcome", item.changedFields.single())
        assertEquals("COMPLETED", item.left?.processingStatus)
        assertEquals("SUPPORTED", item.left?.finalStatus)
        assertEquals("INCOMPLETE", item.right?.processingStatus)
        assertNull(item.right?.finalStatus, "Incomplete processing must not be exposed as a final domain status.")
        val leftReview = item.left?.humanReviews?.single()
        assertEquals("Review is specific to the left run.", leftReview?.note)
        assertEquals(left.summary.id, leftReview?.analysisRunId)
        assertTrue(item.right?.humanReviews.isNullOrEmpty())
        assertFalse(item.changedFields.contains("humanReviews"), "Human Review changes are not machine-result comparison changes.")
    }

    @Test
    fun `pending verification work in a terminal run is reported as incomplete`() {
        val claim = ClaimSpec("The finding is reported.", 10, 33, "reference-a", "The finding is reported [1].")
        val reference = ReferenceSpec("reference-a", "Example study", "10.1234/example")
        val pending = testRun(
            hash = "a".repeat(64),
            configVersion = "same",
            claims = listOf(claim),
            references = listOf(reference),
            outcomes = listOf(OutcomeSpec(0, "PENDING", null)),
        )
        val completed = testRun(
            hash = "a".repeat(64),
            configVersion = "same",
            claims = listOf(claim),
            references = listOf(reference),
            outcomes = listOf(OutcomeSpec(0, "COMPLETED", "SUPPORTED")),
        )

        val item = compare(pending, completed).items.single()

        assertEquals("INCOMPLETE", item.left?.processingStatus)
        assertNull(item.left?.finalStatus)
        assertEquals("COMPLETED", item.right?.processingStatus)
    }

    @Test
    fun `same-source citation stays aligned when reference resolution changes`() {
        val claim = ClaimSpec("The finding is reported.", 10, 33, "reference-a", "The finding is reported [1].")
        val left = testRun(
            hash = "a".repeat(64),
            configVersion = "reference-v1",
            claims = listOf(claim),
            references = listOf(ReferenceSpec("reference-a", "Example study", null, resolutionStatus = "UNRESOLVED")),
        )
        val right = testRun(
            hash = "a".repeat(64),
            configVersion = "reference-v2",
            claims = listOf(claim.copy(referenceKey = "reference-b")),
            references = listOf(ReferenceSpec("reference-b", "Example study", "10.1234/example")),
        )

        val comparison = compare(left, right)

        assertEquals(1, comparison.counts.changed)
        assertEquals(0, comparison.counts.added)
        assertEquals(0, comparison.counts.removed)
        assertEquals(listOf("referenceResolution"), comparison.items.single().changedFields)
    }

    @Test
    fun `a Human Review difference alone leaves equal machine results unchanged`() {
        val leftRunId = UUID.randomUUID()
        val review = HumanReview(
            id = UUID.randomUUID(),
            analysisRunId = leftRunId,
            verificationId = UUID.randomUUID(),
            action = HumanReviewAction.AGREE,
            overrideStatus = null,
            note = "Review only; machine output is unchanged.",
            createdAt = Instant.parse("2026-10-01T00:01:00Z"),
        )
        val claim = ClaimSpec("The finding is reported.", 10, 33, "reference-a", "The finding is reported [1].")
        val reference = ReferenceSpec("reference-a", "Example study", "10.1234/example")
        val left = testRun(
            hash = "a".repeat(64),
            configVersion = "extractor-v1",
            claims = listOf(claim),
            references = listOf(reference),
            outcomes = listOf(OutcomeSpec(0, "COMPLETED", "SUPPORTED", reviews = listOf(review))),
            runId = leftRunId,
        )
        val right = testRun(
            hash = "a".repeat(64),
            configVersion = "extractor-v1",
            claims = listOf(claim),
            references = listOf(reference),
            outcomes = listOf(OutcomeSpec(0, "COMPLETED", "SUPPORTED")),
        )

        val item = compare(left, right).items.single()

        assertEquals("UNCHANGED", item.changeKind)
        assertTrue(item.changedFields.isEmpty())
        assertFalse(item.outcomeChanged)
        assertEquals("Review only; machine output is unchanged.", item.left?.humanReviews?.single()?.note)
        assertTrue(item.right?.humanReviews.isNullOrEmpty())
    }

    @Test
    fun `changed evidence judgements are reported as machine outcome changes rather than passage changes`() {
        val left = comparisonCandidate(judgement = "DIRECT_SUPPORT", role = "PRIMARY_FINDING")
        val right = comparisonCandidate(judgement = "PARTIAL_SUPPORT", role = "AUTHOR_SYNTHESIS")

        val item = AnalysisRunComparisonAlignment().align(listOf(left), listOf(right), sourceOffsetsComparable = true).single()

        assertEquals("CHANGED", item.changeKind)
        assertEquals(listOf("machineOutcome"), item.changedFields)
        assertTrue(item.outcomeChanged)
    }

    @Test
    fun `does not align equal offsets when identical PDF bytes produced different normalized parser text`() {
        val reference = ReferenceSpec("reference", "Study", "10.1234/study")
        val left = testRun(
            hash = "a".repeat(64),
            configVersion = "parser-v1",
            sourceText = "A result was observed.",
            claims = listOf(ClaimSpec("A result", 0, 8, "reference", "A result [1].")),
            references = listOf(reference),
        )
        val right = testRun(
            hash = "a".repeat(64),
            configVersion = "parser-v2",
            sourceText = "B result was observed.",
            claims = listOf(ClaimSpec("B result", 0, 8, "reference", "B result [1].")),
            references = listOf(reference),
        )

        val comparison = compare(left, right)

        assertFalse(comparison.sourceContentDiffers)
        assertEquals(0, comparison.counts.changed)
        assertEquals(1, comparison.counts.removed)
        assertEquals(1, comparison.counts.added)
    }

    @Test
    fun `conflicting reference identifiers do not create a changed-source alignment`() {
        val left = testRun(
            hash = "a".repeat(64),
            configVersion = "same",
            claims = listOf(ClaimSpec("Same finding", 0, 12, "reference-a", "Same finding [1].")),
            references = listOf(
                ReferenceSpec(
                    key = "reference-a",
                    title = "Study",
                    doi = "10.1234/study",
                    reasonCode = "CONFLICTING_DOI_IDENTIFIERS",
                    identifiers = listOf(
                        ParsedBibliographyIdentifier("idno", "DOI", "10.1234/study", "10.1234/study"),
                        ParsedBibliographyIdentifier("ptr", "DOI", "https://doi.org/10.1234/other", "10.1234/other"),
                    ),
                ),
            ),
        )
        val right = testRun(
            hash = "b".repeat(64),
            configVersion = "same",
            claims = listOf(ClaimSpec("Same finding", 5, 17, "reference-b", "Same finding [1].")),
            references = listOf(ReferenceSpec("reference-b", "Study", "10.1234/study")),
        )

        val comparison = compare(left, right)

        assertEquals(2, comparison.counts.unmatched)
        assertEquals(0, comparison.counts.changed)
        assertTrue(comparison.items.all { it.changeKind == "UNMATCHED" })
    }

    @Test
    fun `does not align a syntactically valid DOI that the run marked as conflicting`() {
        val left = testRun(
            hash = "a".repeat(64),
            configVersion = "same",
            claims = listOf(ClaimSpec("Same finding", 0, 12, "reference-a", "Same finding [1].")),
            references = listOf(ReferenceSpec("reference-a", "Study", "10.1234/study", reasonCode = "DOI_TITLE_CONFLICT", resolutionStatus = "UNRESOLVED")),
        )
        val right = testRun(
            hash = "b".repeat(64),
            configVersion = "same",
            claims = listOf(ClaimSpec("Same finding", 5, 17, "reference-b", "Same finding [1].")),
            references = listOf(ReferenceSpec("reference-b", "Study", "10.1234/study")),
        )

        val comparison = compare(left, right)

        assertEquals(2, comparison.counts.unmatched)
        assertEquals(0, comparison.counts.changed)
        assertTrue(comparison.items.all { it.changeKind == "UNMATCHED" })
    }

    @Test
    fun `compares judgements for duplicate passage text at their distinct fused ranks`() {
        val candidate = comparisonCandidate(judgement = "DIRECT_SUPPORT", role = "PRIMARY_FINDING")
        val passage = candidate.result.evidencePassages.single()
        val left = candidate.copy(
            result = candidate.result.copy(
                evidencePassages = listOf(
                    passage.copy(fusedRank = 1, judgement = "DIRECT_SUPPORT"),
                    passage.copy(fusedRank = 2, judgement = "PARTIAL_SUPPORT"),
                ),
            ),
        )
        val right = candidate.copy(
            result = candidate.result.copy(
                evidencePassages = listOf(
                    passage.copy(fusedRank = 1, judgement = "CONTRADICTS"),
                    passage.copy(fusedRank = 2, judgement = "PARTIAL_SUPPORT"),
                ),
            ),
        )

        val item = AnalysisRunComparisonAlignment().align(listOf(left), listOf(right), sourceOffsetsComparable = true).single()

        assertTrue(item.outcomeChanged)
        assertEquals(listOf("machineOutcome"), item.changedFields)
    }

    @Test
    fun `compares a unique passage judgement when its fused rank changes`() {
        val candidate = comparisonCandidate(judgement = "DIRECT_SUPPORT", role = "PRIMARY_FINDING")
        val passage = candidate.result.evidencePassages.single()
        val left = candidate.copy(
            result = candidate.result.copy(evidencePassages = listOf(passage.copy(fusedRank = 1, judgement = "DIRECT_SUPPORT"))),
        )
        val right = candidate.copy(
            result = candidate.result.copy(evidencePassages = listOf(passage.copy(fusedRank = 4, judgement = "PARTIAL_SUPPORT"))),
        )

        val item = AnalysisRunComparisonAlignment().align(listOf(left), listOf(right), sourceOffsetsComparable = true).single()

        assertTrue(item.outcomeChanged)
        assertEquals(listOf("evidencePassages", "machineOutcome"), item.changedFields)
    }

    @Test
    fun `compares judgements when identical asset bytes have different run-scoped asset ids`() {
        val candidate = comparisonCandidate(judgement = "DIRECT_SUPPORT", role = "PRIMARY_FINDING")
        val passage = candidate.result.evidencePassages.single()
        val left = candidate.copy(
            result = candidate.result.copy(
                evidencePassages = listOf(passage.copy(judgement = "DIRECT_SUPPORT")),
            ),
        )
        val right = candidate.copy(
            result = candidate.result.copy(
                evidencePassages = listOf(
                    passage.copy(
                        sourceAssetId = UUID.fromString("00000000-0000-0000-0000-000000000002"),
                        judgement = "PARTIAL_SUPPORT",
                    ),
                ),
            ),
        )

        val item = AnalysisRunComparisonAlignment().align(listOf(left), listOf(right), sourceOffsetsComparable = true).single()

        assertTrue(item.outcomeChanged)
        assertEquals(listOf("evidencePassages", "machineOutcome"), item.changedFields)
    }

    @Test
    fun `malformed DOI text does not create a cross-source alignment identity`() {
        val left = testRun(
            hash = "a".repeat(64),
            configVersion = "same",
            claims = listOf(ClaimSpec("Same finding", 0, 12, "reference-a", "Same finding [1].")),
            references = listOf(ReferenceSpec("reference-a", "Study", "10.not-valid/example")),
        )
        val right = testRun(
            hash = "b".repeat(64),
            configVersion = "same",
            claims = listOf(ClaimSpec("Same finding", 4, 16, "reference-b", "Same finding [1].")),
            references = listOf(ReferenceSpec("reference-b", "Study", "10.not-valid/example")),
        )

        val comparison = compare(left, right)

        assertEquals(2, comparison.counts.unmatched)
        assertEquals(0, comparison.counts.changed)
        assertTrue(comparison.items.all { it.changeKind == "UNMATCHED" })
    }

    @Test
    fun `claim and DOI delimiters cannot collide into a false changed-source alignment`() {
        val left = testRun(
            hash = "a".repeat(64),
            configVersion = "same",
            claims = listOf(ClaimSpec("An observation claim:doi:10.1234/fake", 0, 39, "reference-a", "An observation claim:doi:10.1234/fake [1].")),
            references = listOf(ReferenceSpec("reference-a", "Study A", "10.1234/real")),
        )
        val right = testRun(
            hash = "b".repeat(64),
            configVersion = "same",
            claims = listOf(ClaimSpec("An observation claim", 50, 70, "reference-b", "An observation claim [1].")),
            references = listOf(ReferenceSpec("reference-b", "Study B", "10.1234/fake:doi:10.1234/real")),
        )

        val comparison = compare(left, right)

        assertEquals(0, comparison.counts.changed)
        assertEquals(1, comparison.counts.added)
        assertEquals(1, comparison.counts.removed)
    }

    @Test
    fun `changed source aligns unique exact claims, leaves ambiguous and identity-unknown candidates unmatched, and reports additions and removals`() {
        val left = testRun(
            hash = "a".repeat(64),
            configVersion = "same",
            claims = listOf(
                ClaimSpec("Alpha result", 0, 12, "alpha", "Alpha result [1]."),
                ClaimSpec("Repeated result", 20, 35, "repeat", "Repeated result [2]."),
                ClaimSpec("Removed result", 40, 55, "removed", "Removed result [3]."),
                ClaimSpec("Unresolved result", 60, 78, "unknown", "Unresolved result [4]."),
                ClaimSpec("Known result", 85, 98, "known", "Known result [6]."),
            ),
            references = listOf(
                ReferenceSpec("alpha", "Alpha study", "10.1234/alpha"),
                ReferenceSpec("repeat", "Repeated study", "10.1234/repeat"),
                ReferenceSpec("removed", "Removed study", "10.1234/removed"),
                ReferenceSpec("unknown", "Unknown study", null),
                ReferenceSpec("known", "Known study", "10.1234/known"),
            ),
        )
        val right = testRun(
            hash = "b".repeat(64),
            configVersion = "same",
            claims = listOf(
                ClaimSpec("Alpha result", 5, 17, "alpha-new", "A revised context: Alpha result [1]."),
                ClaimSpec("Repeated result", 22, 37, "repeat-new", "Repeated result [2]."),
                ClaimSpec("Repeated result", 42, 57, "repeat-new", "Repeated result [2]."),
                ClaimSpec("Added result", 65, 77, "added", "Added result [5]."),
                ClaimSpec("Unresolved result", 80, 98, "unknown-new", "Unresolved result [4]."),
                ClaimSpec("Known result", 105, 118, "known-new", "Known result [6]."),
            ),
            references = listOf(
                ReferenceSpec("alpha-new", "Alpha study", "10.1234/alpha"),
                ReferenceSpec("repeat-new", "Repeated study", "10.1234/repeat"),
                ReferenceSpec("added", "Added study", "10.1234/added"),
                ReferenceSpec("unknown-new", "Unknown study", null),
                ReferenceSpec("known-new", "Known study", null),
            ),
        )

        val comparison = compare(left, right)

        assertTrue(comparison.sourceContentDiffers)
        assertEquals(1, comparison.counts.changed)
        assertEquals(1, comparison.counts.added)
        assertEquals(1, comparison.counts.removed)
        assertEquals(7, comparison.counts.unmatched)
        assertEquals(0, comparison.counts.unchanged)
        val ambiguous = comparison.items.filter { it.unmatchedReason == "AMBIGUOUS_CANDIDATES" }
        assertEquals(3, ambiguous.size)
        assertTrue(ambiguous.all { it.left == null || it.right == null })
        assertEquals(3, comparison.items.count { it.unmatchedReason == "NO_COMPATIBLE_REFERENCE_IDENTITY" })
        assertEquals(1, comparison.items.count { it.unmatchedReason == "COUNTERPART_REFERENCE_IDENTITY_UNAVAILABLE" })
        assertTrue(comparison.items.filter { it.left?.claimText == "Known result" || it.right?.claimText == "Known result" }.all { it.changeKind == "UNMATCHED" })
        assertEquals("sourceClaimAndContext", comparison.items.single { it.changeKind == "CHANGED" }.changedFields.single())
        assertTrue(comparison.items.any { it.changeKind == "ADDED" && it.alignmentStatus == "UNPAIRED" && it.right?.claimText == "Added result" })
        assertTrue(comparison.items.any { it.changeKind == "REMOVED" && it.alignmentStatus == "UNPAIRED" && it.left?.claimText == "Removed result" })
    }

    @Test
    fun `duplicate candidates without a peer stay ambiguous instead of being classified as removals`() {
        val left = testRun(
            hash = "a".repeat(64),
            configVersion = "same",
            claims = listOf(
                ClaimSpec("Repeated result", 10, 25, "repeat", "Repeated result [1]."),
                ClaimSpec("Repeated result", 40, 55, "repeat", "Repeated result [1]."),
            ),
            references = listOf(ReferenceSpec("repeat", "Repeated study", "10.1234/repeat")),
        )
        val right = testRun(
            hash = "b".repeat(64),
            configVersion = "same",
            claims = listOf(ClaimSpec("Added result", 70, 82, "added", "Added result [2].")),
            references = listOf(ReferenceSpec("added", "Added study", "10.1234/added")),
        )

        val comparison = compare(left, right)

        assertEquals(2, comparison.counts.unmatched)
        assertEquals(0, comparison.counts.removed)
        assertEquals(1, comparison.counts.added)
        val ambiguous = comparison.items.filter { it.unmatchedReason == "AMBIGUOUS_CANDIDATES" }
        assertEquals(2, ambiguous.size)
        assertTrue(ambiguous.all { it.left != null && it.right == null })
    }

    private fun comparisonCandidate(judgement: String, role: String): ComparisonCandidate {
        val sourceAssetId = UUID.fromString("00000000-0000-0000-0000-000000000001")
        return ComparisonCandidate(
            result = PairResult(
                atomicClaimId = UUID.randomUUID(),
                claimText = "The finding is reported.",
                sourceStartOffset = 10,
                sourceEndOffset = 33,
                citationContextText = "The finding is reported [1].",
                citationTargets = listOf(AnalysisRunComparisonResponse.CitationTarget("[1]", "INFERRED_PROVISIONAL")),
                reference = AnalysisRunComparisonResponse.Reference(
                    localReferenceKey = "reference-a",
                    title = "Example study",
                    rawText = "Example study, 2024",
                    authors = listOf("A. Researcher"),
                    year = 2024,
                    doi = "10.1234/example",
                    referenceType = "JOURNAL_ARTICLE",
                    resolutionStatus = "RESOLVED",
                    resolutionReasonCode = null,
                    matchMethod = "DOI",
                    canonicalPaper = null,
                ),
                citedPaperAccess = null,
                evidencePassages = listOf(
                    EvidencePassage(
                        text = "A retrieved passage.",
                        sectionOrder = 1,
                        sectionHeading = "Results",
                        paragraphStart = 2,
                        paragraphEnd = 2,
                        pageNumber = 4,
                        pageNumbers = listOf(4, 5),
                        vectorRank = 1,
                        lexicalRank = 1,
                        fusedRank = 1,
                        sourceAssetId = sourceAssetId,
                        contentSha256 = "c".repeat(64),
                        parserProvider = "docling",
                        parserVersion = "1.30.0",
                        language = "en",
                        languageDetectorVersion = "test-v1",
                        judgement = judgement,
                        evidenceRole = role,
                    ),
                ),
                processingStatus = "COMPLETED",
                processingFailureReason = null,
                finalStatus = "SUPPORTED",
                verificationScope = "FULL_TEXT",
                terminalReason = null,
                evidenceConflict = false,
                aggregatorVersion = "test-aggregation-v1",
                humanReviews = emptyList(),
            ),
            referenceIdentity = "doi:10.1234/example",
        )
    }

    private fun compare(left: TestRun, right: TestRun): AnalysisRunComparisonResponse {
        val analysisRuns = Mockito.mock(AnalysisRunService::class.java)
        val reports = Mockito.mock(AnalysisRunReportService::class.java)
        listOf(left, right).forEach { fixture ->
            Mockito.`when`(analysisRuns.get(fixture.summary.id)).thenReturn(fixture.summary)
            Mockito.`when`(analysisRuns.getParsedDocument(fixture.summary.id)).thenReturn(fixture.document)
            Mockito.`when`(reports.report(fixture.summary.id)).thenReturn(fixture.report)
        }
        return AnalysisRunComparisonService(analysisRuns, reports).compare(left.summary.id, right.summary.id)
    }

    private fun testRun(
        hash: String,
        configVersion: String,
        claims: List<ClaimSpec>,
        references: List<ReferenceSpec>,
        outcomes: List<OutcomeSpec> = emptyList(),
        runId: UUID = UUID.randomUUID(),
        sourceText: String = "test source text",
    ): TestRun {
        val documentId = UUID.randomUUID()
        val createdAt = Instant.parse("2026-10-01T00:00:00Z")
        val summary = AnalysisRunSummary(
            id = runId,
            documentId = documentId,
            filename = "source.pdf",
            sourceContentSha256 = hash,
            status = "COMPLETED",
            progress = mapper.readTree("{}"),
            configuration = mapper.readTree("""{"claimExtractor":{"provider":"local","version":"$configVersion"}}"""),
            createdAt = createdAt,
            startedAt = createdAt,
            failureReason = null,
        )
        val parsedReferences = references.map { reference ->
            ParsedBibliographyEntryView(
                entryOrder = references.indexOf(reference) + 1,
                localReferenceKey = reference.key,
                rawText = "${reference.title}, 2024",
                title = reference.title,
                authors = listOf("A. Researcher"),
                year = 2024,
                doi = reference.doi,
                referenceType = "JOURNAL_ARTICLE",
                resolutionStatus = reference.resolutionStatus,
                identifiers = reference.identifiers,
            )
        }
        val contexts = claims.map { claim ->
            val claimId = UUID.randomUUID()
            ParsedCitationContextView(
                id = UUID.randomUUID(),
                sectionId = UUID.randomUUID(),
                boundaryKind = "CLAUSE",
                text = claim.context,
                startOffset = claim.start,
                endOffset = claim.end,
                occurrences = emptyList(),
                atomicClaims = listOf(
                    ParsedAtomicClaimView(
                        id = claimId,
                        text = claim.text,
                        sourceStartOffset = claim.start,
                        sourceEndOffset = claim.end,
                        citationTargets = listOf(
                            ParsedClaimCitationTargetView(UUID.randomUUID(), "[1]", claim.referenceKey, claim.referenceKey, "INFERRED_PROVISIONAL"),
                        ),
                    ),
                ),
            )
        }
        val document = ParsedDocumentView(
            parser = ParsedParserProvenance("grobid", "test-v1"),
            sourceContentSha256 = hash,
            normalizedSourceText = sourceText,
            sections = emptyList(),
            citationContexts = contexts,
            bibliographyEntries = parsedReferences,
            bibliographyNormalizationPolicy = BibliographyNormalizationPolicySelection.CURRENT,
        )
        val outcomesByClaimIndex = outcomes.associateBy { it.claimIndex }
        val reportEntries = references.mapIndexed { index, reference ->
            val associatedOutcomes = claims.mapIndexedNotNull { claimIndex, claim ->
                if (claim.referenceKey != reference.key) return@mapIndexedNotNull null
                val specification = outcomesByClaimIndex[claimIndex] ?: return@mapIndexedNotNull null
                val claimId = contexts[claimIndex].atomicClaims.single().id
                val verificationId = UUID.randomUUID()
                CitedReferenceVerificationOutcome(
                    id = verificationId,
                    atomicClaimId = claimId,
                    claimText = claim.text,
                    claimSourceStartOffset = claim.start,
                    claimSourceEndOffset = claim.end,
                    citationContextText = claim.context,
                    citationMarkers = listOf("[1]"),
                    associationKind = "INFERRED_PROVISIONAL",
                    processingStatus = specification.processingStatus,
                    processingFailureReason = specification.failureReason,
                    finalStatus = specification.finalStatus,
                    verificationScope = "FULL_TEXT",
                    terminalReason = null,
                    evidenceConflict = false,
                    aggregatorVersion = "test-aggregation-v1",
                    humanReviews = specification.reviews.map { it.copy(analysisRunId = runId, verificationId = verificationId) },
                )
            }
            BibliographyResolutionReportEntry(
                entryOrder = index + 1,
                localReferenceKey = reference.key,
                rawText = "${reference.title}, 2024",
                title = reference.title,
                authors = listOf("A. Researcher"),
                year = 2024,
                doi = reference.doi,
                referenceType = "JOURNAL_ARTICLE",
                status = reference.resolutionStatus,
                reasonCode = reference.reasonCode,
                identifiers = reference.identifiers,
                canonicalPaper = null,
                confidenceScore = null,
                matchMethod = "DOI",
                verificationOutcomes = associatedOutcomes,
            )
        }
        val report = com.papertrail.api.analysis.report.http.ReferenceResolutionReportResponse(
            analysisRunId = runId,
            runStatus = "COMPLETED",
            referenceResolution = ReferenceResolutionReport(
                executionStatus = "COMPLETED",
                scorePolicyVersion = "reference-policy-v1",
                confidenceThreshold = 0.8,
                summary = ReferenceResolutionSummary(references.size, references.size, 0, 0, 0, 0),
                entries = reportEntries,
            ),
            evidenceCoverage = EvidenceCoverageReport(
                executionStatus = "COMPLETED",
                verificationPolicyVersion = "verification-policy-v1",
                aggregationPolicyVersion = "aggregation-policy-v1",
                thresholds = emptyMap(),
                summary = EvidenceCoverageSummary(totalVerifications = outcomes.size),
            ),
        )
        return TestRun(summary, document, report)
    }

    private data class ClaimSpec(
        val text: String,
        val start: Int,
        val end: Int,
        val referenceKey: String,
        val context: String,
    )

    private data class ReferenceSpec(
        val key: String,
        val title: String,
        val doi: String?,
        val reasonCode: String? = null,
        val identifiers: List<ParsedBibliographyIdentifier> = emptyList(),
        val resolutionStatus: String = "RESOLVED",
    )

    private data class OutcomeSpec(
        val claimIndex: Int,
        val processingStatus: String,
        val finalStatus: String?,
        val failureReason: String? = null,
        val reviews: List<HumanReview> = emptyList(),
    )

    private data class TestRun(
        val summary: AnalysisRunSummary,
        val document: ParsedDocumentView,
        val report: com.papertrail.api.analysis.report.http.ReferenceResolutionReportResponse,
    )
}
