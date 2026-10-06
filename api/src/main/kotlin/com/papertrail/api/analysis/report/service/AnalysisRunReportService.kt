package com.papertrail.api.analysis.report.service

import com.papertrail.api.analysis.report.http.ReferenceResolutionReportResponse
import com.papertrail.api.evidence.report.EvidenceCoverageReport
import com.papertrail.api.evidence.repository.EvidenceCoverageReportRepository
import com.papertrail.api.scholarly.acquisition.repository.CitedPaperAccessRepository
import com.papertrail.api.scholarly.references.report.ReferenceResolutionReport
import com.papertrail.api.scholarly.references.report.ReferenceResolutionSummary
import com.papertrail.api.scholarly.references.repository.ReferenceResolutionRepository
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class AnalysisRunReportService(
    private val referenceResolutionRepository: ReferenceResolutionRepository,
    private val citedPaperAccessRepository: CitedPaperAccessRepository,
    private val evidenceCoverageReportRepository: EvidenceCoverageReportRepository,
) {
    fun report(analysisRunId: UUID): ReferenceResolutionReportResponse? {
        val context = referenceResolutionRepository.loadRun(analysisRunId) ?: return null
        val entries = referenceResolutionRepository.reportEntries(analysisRunId)
        val indexingByReference = evidenceCoverageReportRepository.indexingReportsByReference(analysisRunId)
        val accessByReference = citedPaperAccessRepository.reportEntries(analysisRunId).associate { accessEntry ->
            accessEntry.localReferenceKey to accessEntry.report.copy(
                evidenceIndexing = indexingByReference[accessEntry.bibliographyEntryId],
            )
        }
        val outcomesByReference = evidenceCoverageReportRepository.outcomesByReference(analysisRunId)
        val reportEntries = entries.map { entry ->
            val outcomes = outcomesByReference[entry.localReferenceKey].orEmpty()
            entry.copy(
                citedPaperAccess = accessByReference[entry.localReferenceKey]?.copy(verificationOutcomes = outcomes),
                verificationOutcomes = outcomes,
            )
        }
        val summary = ReferenceResolutionSummary.fromEntries(entries)
        val aggregation = context.configuration.aggregation
        val configuration = context.configuration.referenceResolution
        val policyConfigured = configuration.provider != null && !configuration.scorePolicyVersion.isNullOrBlank() && configuration.confidenceThreshold != null
        return ReferenceResolutionReportResponse(
            analysisRunId = analysisRunId,
            runStatus = context.runStatus,
            evidenceCoverage = EvidenceCoverageReport(
                executionStatus = when {
                    !context.semanticPipelineConfigured -> "NOT_RUN"
                    context.runStatus == "COMPLETED" -> "COMPLETED"
                    context.runStatus == "COMPLETED_WITH_WARNINGS" -> "COMPLETED_WITH_WARNINGS"
                    context.runStatus == "FAILED" -> "FAILED"
                    else -> "PENDING"
                },
                verificationPolicyVersion = aggregation.verificationPolicyVersion,
                aggregationPolicyVersion = aggregation.aggregationPolicyVersion,
                thresholds = aggregation.thresholds,
                summary = evidenceCoverageReportRepository.summary(analysisRunId),
            ),
            referenceResolution = ReferenceResolutionReport(
                executionStatus = when {
                    !policyConfigured || configuration.executionStatus == "NOT_RUN" -> "NOT_RUN"
                    context.runStatus == "COMPLETED_WITH_WARNINGS" -> "COMPLETED_WITH_WARNINGS"
                    summary.notAttempted == 0 && summary.failed == 0 && context.runStatus in setOf("PARSED", "COMPLETED") -> "COMPLETED"
                    else -> "PENDING"
                },
                scorePolicyVersion = configuration.scorePolicyVersion,
                confidenceThreshold = configuration.confidenceThreshold,
                summary = summary,
                entries = reportEntries,
            ),
        )
    }
}
