package com.papertrail.api.analysis.successor.domain

import com.papertrail.api.analysis.configuration.ExternalProviderConsentSnapshot
import java.util.UUID

data class SuccessorRunPlan(
    val batchId: UUID,
    val predecessorAnalysisRunId: UUID,
    val revision: Long,
    val planFingerprint: String,
    val eligible: Boolean,
    val selectedEntries: List<SuccessorSelectedEntryPlan>,
    val retainedReferenceKeys: List<String>,
    val retainedDecision: SuccessorStageDecision,
    val invalidationReasons: List<String>,
    val blockers: List<String>,
    val expectedProviderWork: List<SuccessorExpectedProviderWork>,
    val requiredConsents: List<ExternalProviderConsentSnapshot>,
)

data class SuccessorSelectedEntryPlan(
    val predecessorBibliographyEntryId: UUID,
    val localReferenceKey: String,
    val uploadId: UUID,
    val contentSha256: String,
    val stages: List<SuccessorStageDecision>,
    val blockers: List<String>,
)

data class SuccessorStageDecision(
    val stage: String,
    val disposition: SuccessorWorkDisposition,
    val reasonCode: String,
)

enum class SuccessorWorkDisposition {
    REUSE,
    REPROCESS,
    RETAIN,
}

data class SuccessorExpectedProviderWork(
    val stage: String,
    val providerId: String,
    val trustBoundary: String,
    val dataCategories: List<String>,
    val retentionDisclosure: String?,
)
