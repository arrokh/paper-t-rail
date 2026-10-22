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
import com.papertrail.api.analysis.report.service.AnalysisRunComparisonService
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
        assertEquals(4, comparison.items.count { it.unmatchedReason == "NO_COMPATIBLE_REFERENCE_IDENTITY" })
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
                resolutionStatus = "RESOLVED",
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
            normalizedSourceText = "test source text",
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
                status = "RESOLVED",
                reasonCode = null,
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

    private data class ReferenceSpec(val key: String, val title: String, val doi: String?)

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
