package com.papertrail.api.analysis.service

import com.papertrail.api.analysis.repository.AnalysisRunStageCompletionRepository
import com.papertrail.api.citation.repository.ParsedDocumentRepository
import com.papertrail.api.document.repository.SourceDocumentRepository
import com.papertrail.api.evidence.events.CITED_PAPER_INDEXING_REQUESTED
import com.papertrail.api.external.jev.JevSystemOneSettings
import com.papertrail.api.external.laya.LayaSystemOneSettings
import com.papertrail.api.scholarly.acquisition.events.CITED_PAPER_ACQUISITION_REQUESTED
import com.papertrail.api.scholarly.references.events.REFERENCE_RESOLUTION_REQUESTED
import com.papertrail.api.scholarly.references.service.ReferenceResolutionService
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID

@Component
class AnalysisRunStageCompletionService(
    private val stageCompletionRepository: AnalysisRunStageCompletionRepository,
    private val sourceDocumentRepository: SourceDocumentRepository,
    private val transactionTemplate: TransactionTemplate,
    private val parsedDocumentRepository: ParsedDocumentRepository,
    private val referenceResolutionService: ReferenceResolutionService,
) {
    fun completeParsedStageIfReady(analysisRunId: UUID): Boolean = transactionTemplate.execute {
        sourceDocumentRepository.lockActiveAnalysisRun(analysisRunId)
        val runStatus = stageCompletionRepository.lockRunStatus(analysisRunId)
            ?: throw IllegalArgumentException("Analysis Run not found while completing parsed stage.")
        if (runStatus != "PROCESSING") return@execute false

        val pendingTaskCount = stageCompletionRepository.pendingTaskCount(analysisRunId, REFERENCE_RESOLUTION_REQUESTED)
        val pendingAcquisitionCount = stageCompletionRepository.pendingTaskCount(analysisRunId, CITED_PAPER_ACQUISITION_REQUESTED)
        val pendingIndexingCount = stageCompletionRepository.pendingTaskCount(analysisRunId, CITED_PAPER_INDEXING_REQUESTED)
        if (pendingTaskCount > 0 || pendingAcquisitionCount > 0 || pendingIndexingCount > 0) return@execute false

        val failedReferenceTaskCount = stageCompletionRepository.failedReferenceTaskCount(analysisRunId, REFERENCE_RESOLUTION_REQUESTED)
        val failedAcquisitionTaskCount = stageCompletionRepository.failedAcquisitionTaskCount(analysisRunId, CITED_PAPER_ACQUISITION_REQUESTED)
        val failedEvidenceIndexingCount = stageCompletionRepository.failedEvidenceIndexingCount(analysisRunId)
        val failedTaskCount = failedReferenceTaskCount + failedAcquisitionTaskCount + failedEvidenceIndexingCount
        val parsed = parsedDocumentRepository.find(analysisRunId)
            ?: throw IllegalStateException("Persisted parsed document structure is missing after reference processing.")
        val resolutionSummary = referenceResolutionService.summary(analysisRunId)
        val verificationPipelineConfigured = stageCompletionRepository.verificationPipelineConfigured(analysisRunId)
        val systemOneProviderId = stageCompletionRepository.systemOneProviderId(analysisRunId)
        val systemOneAggregationMode = stageCompletionRepository.systemOneAggregationMode(analysisRunId)
        val systemOneEvaluationOnly = systemOneAggregationMode == "JUDGEMENT_ONLY"
        val systemOneAggregation = systemOneAggregationMode == "AGGREGATION"
        val systemOneJudgementCount = if (systemOneEvaluationOnly || systemOneAggregation) {
            stageCompletionRepository.systemOneJudgementCount(analysisRunId, systemOneProviderId)
        } else {
            0L
        }
        val layaSpanCounts = if (
            systemOneProviderId == LayaSystemOneSettings.PROVIDER_ID &&
            (systemOneEvaluationOnly || systemOneAggregation)
        ) {
            stageCompletionRepository.layaSpanCounts(analysisRunId)
        } else {
            AnalysisRunStageCompletionRepository.LayaSpanCounts(0, 0, 0)
        }
        val systemOneDisplayName = when (systemOneProviderId) {
            LayaSystemOneSettings.PROVIDER_ID -> "Laya"
            JevSystemOneSettings.PROVIDER_ID -> "Jev"
            else -> "System One"
        }
        if (verificationPipelineConfigured) stageCompletionRepository.markOrphanedPendingVerifications(analysisRunId)
        val verificationCounts = stageCompletionRepository.verificationCounts(analysisRunId)
        val evaluationFailureCounts = if (systemOneEvaluationOnly || systemOneAggregation) {
            stageCompletionRepository.evaluationFailureCounts(analysisRunId)
        } else {
            AnalysisRunStageCompletionRepository.EvaluationFailureCounts(0L, 0L)
        }
        val failedEvaluationPairCount = evaluationFailureCounts.failedPairCount
        val failedSystemOneEvaluationCount = evaluationFailureCounts.failedSystemOneCount
        val atomicClaims = parsed.citationContexts.flatMap { it.atomicClaims }
        val inferredClaimTargetLinkCount = atomicClaims.sumOf { it.citationTargets.size }
        val warning = failedTaskCount > 0 ||
            (verificationPipelineConfigured && verificationCounts.incomplete > 0) ||
            (systemOneEvaluationOnly && failedEvaluationPairCount > 0) ||
            ((systemOneEvaluationOnly || systemOneAggregation) && layaSpanCounts.incomplete > 0)
        val finalStatus = when {
            verificationPipelineConfigured && warning -> "COMPLETED_WITH_WARNINGS"
            verificationPipelineConfigured -> "COMPLETED"
            warning -> "COMPLETED_WITH_WARNINGS"
            else -> "PARSED"
        }
        val progressMessage = when {
            systemOneAggregation && warning && layaSpanCounts.verifications > 0 -> "$systemOneDisplayName aggregation used $systemOneJudgementCount uncalibrated Evidence Judgement(s) and ${layaSpanCounts.completed} diagnostic span judgement(s). Laya V1 span diagnostics for ${layaSpanCounts.verifications} Claim–Reference pair(s) are not rolled up into parent or final statuses; ${verificationCounts.incomplete} pair(s) remain incomplete. Results remain uncalibrated."
            systemOneAggregation && verificationCounts.fullTextPairs == 0 && warning -> "Analysis Run completed with warnings for ${verificationCounts.total} Claim–Reference Verification pair(s). No eligible full-text evidence was available, so $systemOneDisplayName assessment and evidence aggregation were not run; ${verificationCounts.incomplete} pair(s) remain incomplete. Results remain uncalibrated and have no attached Human Review."
            systemOneAggregation && verificationCounts.fullTextPairs == 0 -> "Analysis Run completed with terminal outcomes for ${verificationCounts.total} Claim–Reference Verification pair(s). No eligible full-text evidence was available, so $systemOneDisplayName assessment and evidence aggregation were not run. Results remain uncalibrated and have no attached Human Review."
            systemOneAggregation && warning -> "$systemOneDisplayName aggregation used $systemOneJudgementCount uncalibrated Evidence Judgement(s) and experimental thresholds, but ${verificationCounts.incomplete} Claim–Reference Verification pair(s) are incomplete. Results remain uncalibrated."
            systemOneAggregation -> "$systemOneDisplayName aggregation completed for ${verificationCounts.aggregatedPairs} full-text Claim–Reference Verification pair(s) using $systemOneJudgementCount uncalibrated Evidence Judgement(s) and experimental thresholds. Results remain uncalibrated and have no attached Human Review."
            verificationPipelineConfigured && warning -> "The Evidence Coverage Report is ready, but ${verificationCounts.incomplete} Claim–Reference Verification pair(s) are incomplete after processing failures."
            verificationPipelineConfigured -> "The Evidence Coverage Report is complete for ${verificationCounts.total} Claim–Reference Verification pair(s)."
            systemOneEvaluationOnly && warning && layaSpanCounts.incomplete > 0 -> "$systemOneDisplayName produced $systemOneJudgementCount uncalibrated Evidence Judgement(s) and ${layaSpanCounts.completed} diagnostic span judgement(s), but ${layaSpanCounts.incomplete} required Laya span(s) are missing or incomplete; final Claim–Paper Verification remains NOT_RUN."
            systemOneEvaluationOnly && warning && failedSystemOneEvaluationCount > 0 && systemOneJudgementCount > 0 -> "$systemOneDisplayName produced $systemOneJudgementCount uncalibrated Evidence Judgement(s), but $failedSystemOneEvaluationCount Claim–Reference pair(s) could not be judged; final Claim–Paper Verification remains NOT_RUN."
            systemOneEvaluationOnly && warning && failedSystemOneEvaluationCount > 0 -> "$systemOneDisplayName evaluation left $failedSystemOneEvaluationCount Claim–Reference pair(s) unjudged after System One assessment failed; final Claim–Paper Verification remains NOT_RUN."
            systemOneEvaluationOnly && warning && failedEvaluationPairCount > 0 && systemOneJudgementCount > 0 -> "$systemOneDisplayName produced $systemOneJudgementCount uncalibrated Evidence Judgement(s), but $failedEvaluationPairCount Claim–Reference pair(s) could not be assessed because reference or evidence processing failed; final Claim–Paper Verification remains NOT_RUN."
            systemOneEvaluationOnly && warning && failedEvaluationPairCount > 0 -> "$systemOneDisplayName could not assess $failedEvaluationPairCount Claim–Reference pair(s) because reference or evidence processing failed; final Claim–Paper Verification remains NOT_RUN."
            systemOneEvaluationOnly && warning && systemOneJudgementCount > 0 -> "$systemOneDisplayName produced $systemOneJudgementCount uncalibrated Evidence Judgement(s), but $failedTaskCount pipeline task(s) failed; final Claim–Paper Verification remains NOT_RUN."
            systemOneEvaluationOnly && warning -> "$systemOneDisplayName evaluation was incomplete because $failedTaskCount pipeline task(s) failed; final Claim–Paper Verification remains NOT_RUN."
            warning -> "Parsed structure is ready, but $failedTaskCount reference-resolution, cited-paper access, or Evidence Passage indexing task(s) failed; semantic verification has not run."
            systemOneEvaluationOnly && (systemOneJudgementCount > 0 || layaSpanCounts.completed > 0) -> "$systemOneDisplayName produced $systemOneJudgementCount uncalibrated Evidence Judgement(s) and ${layaSpanCounts.completed} diagnostic span judgement(s); final Claim–Paper Verification remains NOT_RUN."
            systemOneEvaluationOnly -> "$systemOneDisplayName is selected for assessment, but no eligible full-text Evidence Passage was available; no Evidence Judgement was produced and final verification remains NOT_RUN."
            else -> "Parsed structure and reference resolution are ready; semantic verification was not configured for this Analysis Run."
        }
        val updated = stageCompletionRepository.updateCompletion(
            analysisRunId,
            AnalysisRunStageCompletionRepository.CompletionUpdate(
                finalStatus = finalStatus,
                progressMessage = progressMessage,
                sectionCount = parsed.sections.size,
                citationContextCount = parsed.citationContexts.size,
                citationOccurrenceCount = parsed.citationContexts.sumOf { it.occurrences.size },
                bibliographyEntryCount = parsed.bibliographyEntries.size,
                atomicClaimCount = atomicClaims.size,
                inferredClaimTargetLinkCount = inferredClaimTargetLinkCount,
                resolvedReferenceCount = resolutionSummary.resolved,
                unresolvedReferenceCount = resolutionSummary.unresolved,
                unsupportedReferenceTypeCount = resolutionSummary.unsupportedReferenceType,
                notAttemptedReferenceCount = resolutionSummary.notAttempted,
                failedReferenceResolutionCount = resolutionSummary.failed,
                acquiredCitedPaperCount = stageCompletionRepository.fullTextAccessCount(analysisRunId),
                failedCitedPaperAcquisitionCount = failedAcquisitionTaskCount,
                indexedCitedPaperCount = stageCompletionRepository.completedIndexingCount(analysisRunId),
                failedEvidenceIndexingCount = failedEvidenceIndexingCount,
                totalVerifications = verificationCounts.total,
                completedVerifications = verificationCounts.completed,
                incompleteVerifications = verificationCounts.incomplete,
                warning = warning,
                failureReason = if (verificationCounts.incomplete > 0) {
                    "One or more Claim–Reference Verification pairs are incomplete because processing did not finish."
                } else {
                    "One or more reference-resolution, cited-paper access, or Evidence Passage indexing tasks failed."
                },
                markCompleted = verificationPipelineConfigured || warning,
            ),
        )
        if (!updated) throw IllegalStateException("Analysis Run could not complete its parsed stage.")
        val completionLog = logger.atInfo()
        if (MDC.get("analysisRunId") == null) {
            completionLog.addKeyValue("analysisRunId", analysisRunId)
        }
        completionLog
            .addKeyValue("runStatus", finalStatus)
            .addKeyValue("systemOneProviderId", systemOneProviderId)
            .addKeyValue("systemOneMode", systemOneAggregationMode)
            .addKeyValue("failedPipelineTaskCount", failedTaskCount)
            .addKeyValue("resolvedReferenceCount", resolutionSummary.resolved)
            .addKeyValue("unresolvedReferenceCount", resolutionSummary.unresolved)
            .addKeyValue("unsupportedReferenceTypeCount", resolutionSummary.unsupportedReferenceType)
            .addKeyValue("failedReferenceResolutionCount", resolutionSummary.failed)
            .addKeyValue("verificationCount", verificationCounts.total)
            .addKeyValue("completedVerificationCount", verificationCounts.completed)
            .addKeyValue("incompleteVerificationCount", verificationCounts.incomplete)
            .addKeyValue("fullTextPairCount", verificationCounts.fullTextPairs)
            .addKeyValue("evidenceJudgementCount", systemOneJudgementCount)
            .addKeyValue("failedEvaluationPairCount", failedEvaluationPairCount)
            .addKeyValue("failedSystemOneEvaluationCount", failedSystemOneEvaluationCount)
            .addKeyValue("warning", warning)
            .log("Analysis Run processing stage completed")
        true
    } ?: false

    private val logger = LoggerFactory.getLogger(AnalysisRunStageCompletionService::class.java)
}
