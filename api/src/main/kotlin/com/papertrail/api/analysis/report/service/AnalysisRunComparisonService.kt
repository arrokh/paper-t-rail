package com.papertrail.api.analysis.report.service

import com.papertrail.api.analysis.http.AnalysisRunSummary
import com.papertrail.api.analysis.http.ParsedAtomicClaimView
import com.papertrail.api.analysis.http.ParsedBibliographyEntryView
import com.papertrail.api.analysis.http.ParsedClaimCitationTargetView
import com.papertrail.api.analysis.http.ParsedDocumentView
import com.papertrail.api.analysis.report.http.AnalysisRunComparisonResponse
import com.papertrail.api.analysis.service.AnalysisRunService
import com.papertrail.api.evidence.report.CitedReferenceVerificationOutcome
import com.papertrail.api.analysis.report.http.AnalysisRunComparisonResponse.CanonicalPaper
import com.papertrail.api.analysis.report.http.AnalysisRunComparisonResponse.CitedPaperAccess
import com.papertrail.api.analysis.report.http.AnalysisRunComparisonResponse.EvidencePassage
import com.papertrail.api.analysis.report.http.AnalysisRunComparisonResponse.PairResult
import com.papertrail.api.analysis.report.http.AnalysisRunComparisonResponse.Reference
import com.papertrail.api.analysis.report.http.AnalysisRunComparisonResponse.Run
import com.papertrail.api.scholarly.references.report.BibliographyResolutionReportEntry
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

@Service
class AnalysisRunComparisonService(
    private val analysisRunService: AnalysisRunService,
    private val analysisRunReportService: AnalysisRunReportService,
) {
    private val alignment = AnalysisRunComparisonAlignment()

    fun compare(leftRunId: UUID, rightRunId: UUID): AnalysisRunComparisonResponse {
        val leftRun = requireRun(leftRunId)
        val rightRun = requireRun(rightRunId)
        val leftDocument = analysisRunService.getParsedDocument(leftRunId)
        val rightDocument = analysisRunService.getParsedDocument(rightRunId)
        val leftReport = analysisRunReportService.report(leftRunId)
            ?: throw ResponseStatusException(HttpStatus.CONFLICT, "A persisted report is not available for the left Analysis Run.")
        val rightReport = analysisRunReportService.report(rightRunId)
            ?: throw ResponseStatusException(HttpStatus.CONFLICT, "A persisted report is not available for the right Analysis Run.")
        val sameSourceContent = leftRun.sourceContentSha256 == rightRun.sourceContentSha256
        val leftPairs = buildPairs(leftRun, leftDocument, leftReport.referenceResolution.entries, sameSourceContent)
        val rightPairs = buildPairs(rightRun, rightDocument, rightReport.referenceResolution.entries, sameSourceContent)
        val items = alignment.align(leftPairs, rightPairs, sameSourceContent)
        val counts = AnalysisRunComparisonResponse.Counts(
            unchanged = items.count { it.changeKind == "UNCHANGED" },
            changed = items.count { it.changeKind == "CHANGED" },
            added = items.count { it.changeKind == "ADDED" },
            removed = items.count { it.changeKind == "REMOVED" },
            unmatched = items.count { it.changeKind == "UNMATCHED" },
            outcomeChanges = items.count { it.outcomeChanged },
        )

        return AnalysisRunComparisonResponse(
            alignmentPolicy = AnalysisRunComparisonResponse.AlignmentPolicy(
                id = ALIGNMENT_POLICY_ID,
                version = ALIGNMENT_POLICY_VERSION,
                description = ALIGNMENT_POLICY_DESCRIPTION,
            ),
            sourceContentDiffers = !sameSourceContent,
            sourceDocumentDiffers = leftRun.documentId != rightRun.documentId,
            analysisConfigurationDiffers = leftRun.configuration != rightRun.configuration,
            leftRun = leftRun.toComparisonRun(leftReport.runStatus, leftReport.referenceResolution.executionStatus, leftReport.evidenceCoverage.executionStatus),
            rightRun = rightRun.toComparisonRun(rightReport.runStatus, rightReport.referenceResolution.executionStatus, rightReport.evidenceCoverage.executionStatus),
            counts = counts,
            items = items,
        )
    }

    private fun requireRun(runId: UUID): AnalysisRunSummary = analysisRunService.get(runId)
        ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Analysis Run not found.")

    private fun AnalysisRunSummary.toComparisonRun(
        reportStatus: String,
        referenceResolutionStatus: String,
        evidenceCoverageStatus: String,
    ) = Run(
        analysisRunId = id,
        sourceDocumentId = documentId,
        filename = filename,
        sourceContentSha256 = sourceContentSha256,
        status = status.ifBlank { reportStatus },
        createdAt = createdAt,
        startedAt = startedAt,
        failureReason = failureReason,
        pipeline = pipeline,
        referenceResolutionExecutionStatus = referenceResolutionStatus,
        evidenceCoverageExecutionStatus = evidenceCoverageStatus,
        configuration = configuration,
    )

    private fun buildPairs(
        run: AnalysisRunSummary,
        document: ParsedDocumentView,
        reportEntries: List<BibliographyResolutionReportEntry>,
        sameSourceContent: Boolean,
    ): List<ComparisonCandidate> {
        val parsedEntries = document.bibliographyEntries.associateBy { it.localReferenceKey }
        val reportEntriesByKey = reportEntries.associateBy { it.localReferenceKey }
        return document.citationContexts.flatMap { context ->
            context.atomicClaims.flatMap { claim ->
                claim.citationTargets.groupBy { it.bibliographyReferenceKey }.map { (referenceKey, targets) ->
                    val parsedReference = parsedEntries[referenceKey]
                    val reportReference = reportEntriesByKey[referenceKey]
                    val outcome = reportReference?.verificationOutcomes?.firstOrNull { it.atomicClaimId == claim.id }
                    val pair = claim.toPairResult(
                        contextText = context.text,
                        referenceKey = referenceKey,
                        targets = targets,
                        parsedReference = parsedReference,
                        reportReference = reportReference,
                        outcome = outcome,
                        runStatus = run.status,
                        failureReason = run.failureReason,
                    )
                    ComparisonCandidate(pair, referenceIdentityForAlignment(parsedReference, reportReference, sameSourceContent))
                }
            }
        }
    }

    private fun ParsedAtomicClaimView.toPairResult(
        contextText: String,
        referenceKey: String,
        targets: List<ParsedClaimCitationTargetView>,
        parsedReference: ParsedBibliographyEntryView?,
        reportReference: BibliographyResolutionReportEntry?,
        outcome: CitedReferenceVerificationOutcome?,
        runStatus: String,
        failureReason: String?,
    ): PairResult {
        val accessReport = reportReference?.citedPaperAccess
        val passages = outcome?.evidencePassages.orEmpty()
        val indexing = accessReport?.evidenceIndexing
        val normalizedProcessingStatus = when (outcome?.processingStatus) {
            "COMPLETED" -> "COMPLETED"
            "INCOMPLETE", "FAILED" -> "INCOMPLETE"
            "PENDING" -> "PENDING"
            else -> if (runStatus in INCOMPLETE_RUN_STATUSES) "INCOMPLETE" else "PENDING"
        }
        val reference = Reference(
            localReferenceKey = referenceKey,
            title = reportReference?.title ?: parsedReference?.title ?: targets.firstOrNull()?.bibliographyTitle,
            rawText = reportReference?.rawText ?: parsedReference?.rawText.orEmpty(),
            authors = reportReference?.authors ?: parsedReference?.authors.orEmpty(),
            year = reportReference?.year ?: parsedReference?.year,
            doi = reportReference?.doi ?: parsedReference?.doi,
            referenceType = reportReference?.referenceType ?: parsedReference?.referenceType ?: "UNKNOWN",
            resolutionStatus = reportReference?.status ?: parsedReference?.resolutionStatus ?: "UNKNOWN",
            resolutionReasonCode = reportReference?.reasonCode,
            matchMethod = reportReference?.matchMethod,
            canonicalPaper = reportReference?.canonicalPaper?.let { canonical ->
                CanonicalPaper(canonical.id, canonical.doi, canonical.title, canonical.authors, canonical.year)
            },
        )
        val citedPaperAccess = accessReport?.let { access ->
            CitedPaperAccess(
                accessStatus = access.accessStatus,
                accessOrigin = access.accessOrigin.name,
                accessReason = access.accessReason,
                accessReasons = access.accessReasons,
                accessProgressStatus = reportReference.accessProgressStatus,
                accessProgressReason = reportReference.accessProgressReason,
                indexingStatus = indexing?.status,
                sourceAssetId = indexing?.assetId ?: passages.firstOrNull()?.sourceAssetId,
                contentSha256 = indexing?.contentSha256 ?: access.contentSha256 ?: passages.firstOrNull()?.contentSha256,
                parserProvider = indexing?.parserProvider ?: passages.firstOrNull()?.parserProvider,
                parserVersion = indexing?.parserVersion ?: passages.firstOrNull()?.parserVersion,
                language = indexing?.language ?: passages.firstOrNull()?.language,
                languageDetectorVersion = indexing?.languageDetectorVersion ?: passages.firstOrNull()?.languageDetectorVersion,
            )
        }
        return PairResult(
            atomicClaimId = id,
            claimText = text,
            sourceStartOffset = sourceStartOffset,
            sourceEndOffset = sourceEndOffset,
            citationContextText = contextText,
            citationTargets = targets.map { AnalysisRunComparisonResponse.CitationTarget(it.markerText, it.associationKind) },
            reference = reference,
            citedPaperAccess = citedPaperAccess,
            evidencePassages = passages.map { passage ->
                EvidencePassage(
                    text = passage.text,
                    sectionOrder = passage.sectionOrder,
                    sectionHeading = passage.sectionHeading,
                    paragraphStart = passage.paragraphStart,
                    paragraphEnd = passage.paragraphEnd,
                    pageNumber = passage.pageNumber,
                    vectorRank = passage.vectorRank,
                    lexicalRank = passage.lexicalRank,
                    fusedRank = passage.fusedRank,
                    sourceAssetId = passage.sourceAssetId,
                    contentSha256 = passage.contentSha256,
                    parserProvider = passage.parserProvider,
                    parserVersion = passage.parserVersion,
                    language = passage.language,
                    languageDetectorVersion = passage.languageDetectorVersion,
                    judgement = passage.evidenceJudgement?.judgement,
                    evidenceRole = passage.evidenceJudgement?.evidenceRole,
                )
            },
            processingStatus = normalizedProcessingStatus,
            processingFailureReason = outcome?.processingFailureReason ?: failureReason.takeIf { normalizedProcessingStatus == "INCOMPLETE" },
            finalStatus = outcome?.finalStatus?.takeIf { normalizedProcessingStatus == "COMPLETED" },
            verificationScope = outcome?.verificationScope,
            terminalReason = outcome?.terminalReason,
            evidenceConflict = outcome?.evidenceConflict,
            aggregatorVersion = outcome?.aggregatorVersion,
            humanReviews = outcome?.humanReviews.orEmpty(),
        )
    }

    private fun referenceIdentityForAlignment(
        parsedReference: ParsedBibliographyEntryView?,
        reportReference: BibliographyResolutionReportEntry?,
        sameSourceContent: Boolean,
    ): String? = strongReferenceIdentity(parsedReference, reportReference)
        ?: if (sameSourceContent) {
            parsedReference?.sourceLocalReferenceKey?.takeIf(String::isNotBlank)?.let { "source-key:$it" }
                ?: parsedReference?.localReferenceKey?.takeIf { parsedReference.localReferenceKeyOrigin == "GROBID_XML_ID" }?.let { "source-key:$it" }
                ?: parsedReference?.rawText?.let(::normalizeReferenceText)?.takeIf(String::isNotBlank)?.let { "source-text:$it" }
        } else null

    private fun strongReferenceIdentity(
        parsedReference: ParsedBibliographyEntryView?,
        reportReference: BibliographyResolutionReportEntry?,
    ): String? {
        val doi = listOf(reportReference?.doi, reportReference?.canonicalPaper?.doi, parsedReference?.doi)
            .firstNotNullOfOrNull(::normalizeDoi)
        if (doi != null) return "doi:$doi"
        val canonicalPaperId = reportReference?.canonicalPaper?.id ?: return null
        return "canonical:${canonicalPaperId}"
    }

    private fun normalizeDoi(value: String?): String? = value
        ?.trim()
        ?.lowercase()
        ?.replace(DOI_PREFIX, "")
        ?.removePrefix("doi:")
        ?.trim()
        ?.trimEnd('.')
        ?.takeIf(String::isNotBlank)

    private fun normalizeReferenceText(value: String): String = value.trim().replace(WHITESPACE, " ").lowercase()

    private companion object {
        const val ALIGNMENT_POLICY_ID = "conservative-source-span-reference-identity"
        const val ALIGNMENT_POLICY_VERSION = "1"
        const val ALIGNMENT_POLICY_DESCRIPTION = "For identical Source Document content, align only unique Atomic Claim source-span and compatible Cited Reference identity pairs. For changed content, align only unique exact normalized Atomic Claim text and DOI or Canonical Paper identity pairs. Ambiguous candidates and pairs without a compatible reference identity remain separate and unmatched; no fuzzy claim matching is performed."
        val DOI_PREFIX = Regex("^https?://(?:dx\\.)?doi\\.org/", RegexOption.IGNORE_CASE)
        val WHITESPACE = Regex("\\s+")
        val INCOMPLETE_RUN_STATUSES = setOf("FAILED", "COMPLETED_WITH_WARNINGS", "COMPLETED")
    }
}
