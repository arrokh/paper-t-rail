package com.papertrail.api.analysis.successor.service

import com.fasterxml.jackson.databind.JsonNode
import com.papertrail.api.analysis.configuration.AnalysisConfigurationSnapshot
import com.papertrail.api.analysis.configuration.ProviderSelection
import com.papertrail.api.analysis.configuration.RunConfigurationFactory
import com.papertrail.api.analysis.recovery.domain.RecoveryAssetSelectionMethod
import com.papertrail.api.analysis.recovery.domain.RecoveryIdentityOutcome
import com.papertrail.api.analysis.recovery.domain.RecoveryStagingException
import com.papertrail.api.analysis.recovery.domain.RecoveryUpload
import com.papertrail.api.analysis.recovery.domain.RecoveryUploadAssetSelection
import com.papertrail.api.analysis.recovery.domain.RecoveryUploadStatus
import com.papertrail.api.analysis.recovery.domain.RecoveryUploadValidationAttempt
import com.papertrail.api.analysis.recovery.domain.RecoveryValidationStatus
import com.papertrail.api.analysis.recovery.service.RecoveryUploadValidationService
import com.papertrail.api.analysis.successor.domain.SuccessorExpectedProviderWork
import com.papertrail.api.analysis.successor.domain.SuccessorRunPlan
import com.papertrail.api.analysis.successor.domain.SuccessorSelectedEntryPlan
import com.papertrail.api.analysis.successor.domain.SuccessorStageDecision
import com.papertrail.api.analysis.successor.domain.SuccessorWorkDisposition
import com.papertrail.api.analysis.successor.http.SuccessorRunPreviewRequest
import com.papertrail.api.analysis.successor.repository.SuccessorEntrySnapshot
import com.papertrail.api.analysis.successor.repository.SuccessorRunPreviewRepository
import com.papertrail.api.analysis.successor.repository.SuccessorReferenceKey
import com.papertrail.api.analysis.recovery.domain.RecoveryLanguageEligibility
import com.papertrail.api.analysis.recovery.repository.RecoveryBatchRepository
import com.papertrail.api.infrastructure.crypto.sha256Hex
import com.papertrail.api.utils.JsonUtil
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID

@Service
class SuccessorRunService(
    private val recoveryBatchRepository: RecoveryBatchRepository,
    private val recoveryUploadValidationService: RecoveryUploadValidationService,
    private val previewRepository: SuccessorRunPreviewRepository,
    private val configurationFactory: RunConfigurationFactory,
) {
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun preview(batchId: UUID, request: SuccessorRunPreviewRequest): SuccessorRunPlan {
        val batch = recoveryBatchRepository.activeBatchById(batchId, Instant.now())
        if (batch.revision != request.expectedRevision) {
            throw RecoveryStagingException(
                "RECOVERY_BATCH_REVISION_STALE",
                409,
                "Recovery Batch changed; refresh its current selections before previewing.",
            )
        }

        val predecessor = previewRepository.predecessor(batch.analysisRunId)
            ?: throw RecoveryStagingException("ANALYSIS_RUN_NOT_FOUND", 404, "Analysis Run not found.")
        val configuration = try {
            configurationFactory.from(request.configuration)
        } catch (_: IllegalArgumentException) {
            throw RecoveryStagingException(
                "SUCCESSOR_CONFIGURATION_INVALID",
                400,
                "The successor configuration or its required provider consent is invalid.",
            )
        }
        val invalidationReasons = configurationInvalidations(predecessor.configuration, configuration)
        val referenceKeys = previewRepository.referenceKeys(batch.analysisRunId)
        val referenceOrder = referenceKeys.withIndex().associate { (index, reference) -> reference.bibliographyEntryId to index }
        val selectedEntryPlans = batch.selectedAssets
            .map { selection ->
                val upload = batch.uploads.singleOrNull { it.id == selection.uploadId }
                val entry = previewRepository.selectedEntry(batch.analysisRunId, selection.bibliographyEntryId)
                val attempt = recoveryUploadValidationService.latest(batch.id, selection.uploadId)
                toSelectedEntryPlan(
                    selection,
                    upload,
                    entry,
                    attempt?.takeIf { it.id == selection.validationAttemptId },
                    invalidationReasons,
                )
            }
            .sortedBy { plan -> referenceOrder[plan.predecessorBibliographyEntryId] ?: Int.MAX_VALUE }

        val blockers = buildList {
            if (predecessor.status !in COMPLETED_RUN_STATUSES) add("PREDECESSOR_RUN_NOT_COMPLETED")
            if (!predecessor.hasSourceParse) add("PREDECESSOR_SOURCE_PARSE_UNAVAILABLE")
            if (predecessor.bibliographyEntryCount == 0L) add("PREDECESSOR_BIBLIOGRAPHY_UNAVAILABLE")
            if (predecessor.claimCount == 0L) add("PREDECESSOR_CLAIMS_UNAVAILABLE")
            if (selectedEntryPlans.isEmpty()) add("NO_CURRENT_SELECTED_ASSETS")
            if (invalidationReasons.isNotEmpty()) add("CONFIGURATION_CHANGE_REQUIRES_UNSUPPORTED_GLOBAL_REPROCESSING")
            if (!hasMatchingExternalProviderConsents(predecessor.configuration)) add("PREDECESSOR_EXTERNAL_PROVIDER_CONSENT_MISSING_OR_MISMATCHED")
            selectedEntryPlans.flatMap(SuccessorSelectedEntryPlan::blockers).distinct().forEach(::add)
        }
        val selectedIds = selectedEntryPlans.map(SuccessorSelectedEntryPlan::predecessorBibliographyEntryId).toSet()
        val retainedReferenceKeys = referenceKeys
            .filterNot { it.bibliographyEntryId in selectedIds }
            .map(SuccessorReferenceKey::localReferenceKey)
        val retainedDecision = SuccessorStageDecision(
            stage = "unselected-bibliography-coverage",
            disposition = SuccessorWorkDisposition.RETAIN,
            reasonCode = "UNSELECTED_COVERAGE_PRESERVED",
        )
        val expectedProviderWork = if (selectedEntryPlans.isEmpty()) {
            emptyList()
        } else {
            listOf(
                configuration.embedding.toExpectedWork("evidence-embedding"),
                configuration.systemOne.toExpectedWork("claim-verification"),
            )
        }
        val fingerprintInput = SuccessorPlanFingerprintInput(
            batchId = batch.id,
            predecessorAnalysisRunId = batch.analysisRunId,
            revision = batch.revision,
            predecessorSourceSha256 = predecessor.sourceContentSha256,
            configuration = configuration,
            selectedEntries = selectedEntryPlans,
            retainedReferenceKeys = retainedReferenceKeys,
            invalidationReasons = invalidationReasons,
            blockers = blockers,
        )
        val fingerprint = sha256Hex(canonicalJson(JsonUtil.toTree(fingerprintInput)).toByteArray(StandardCharsets.UTF_8))

        return SuccessorRunPlan(
            batchId = batch.id,
            predecessorAnalysisRunId = batch.analysisRunId,
            revision = batch.revision,
            planFingerprint = fingerprint,
            eligible = blockers.isEmpty(),
            selectedEntries = selectedEntryPlans,
            retainedReferenceKeys = retainedReferenceKeys,
            retainedDecision = retainedDecision,
            invalidationReasons = invalidationReasons,
            blockers = blockers,
            expectedProviderWork = expectedProviderWork,
            requiredConsents = configuration.externalProviderConsents,
        )
    }

    private fun toSelectedEntryPlan(
        selection: RecoveryUploadAssetSelection,
        upload: RecoveryUpload?,
        entry: SuccessorEntrySnapshot?,
        attempt: RecoveryUploadValidationAttempt?,
        invalidationReasons: List<String>,
    ): SuccessorSelectedEntryPlan {
        val blockers = buildList {
            if (upload == null || upload.status != RecoveryUploadStatus.STAGED || upload.actualSha256 != selection.contentSha256) {
                add("SELECTED_UPLOAD_NOT_STAGED_AT_RECORDED_HASH")
            }
            if (attempt == null || attempt.contentSha256 != selection.contentSha256) {
                add("CURRENT_UPLOAD_VALIDATION_REQUIRED")
            } else {
                if (attempt.validationStatus != RecoveryValidationStatus.COMPLETED) add("UPLOAD_VALIDATION_NOT_COMPLETED")
                if (attempt.languageEligibility != RecoveryLanguageEligibility.ELIGIBLE || !attempt.detectedLanguage.equals("en", ignoreCase = true)) {
                    add("UPLOAD_NOT_CONFIRMED_ENGLISH_ELIGIBLE")
                }
                if (attempt.identityOutcome !in setOf(RecoveryIdentityOutcome.VALIDATED, RecoveryIdentityOutcome.NEEDS_CONFIRMATION)) {
                    add("UPLOAD_IDENTITY_NOT_ACCEPTED")
                }
                if (attempt.identityOutcome == RecoveryIdentityOutcome.NEEDS_CONFIRMATION && selection.selectionMethod != RecoveryAssetSelectionMethod.HUMAN_CONFIRMED) {
                    add("HUMAN_IDENTITY_CONFIRMATION_REQUIRED")
                }
            }
            if (entry == null) {
                add("SELECTED_BIBLIOGRAPHY_ENTRY_NOT_FOUND")
            } else {
                if (entry.resolutionStatus != "RESOLVED" || entry.canonicalPaperId == null) add("SELECTED_ENTRY_NOT_RESOLVED")
                if (entry.claimCount == 0L) add("SELECTED_ENTRY_HAS_NO_CITATION_CLAIMS")
            }
        }
        return SuccessorSelectedEntryPlan(
            predecessorBibliographyEntryId = selection.bibliographyEntryId,
            localReferenceKey = entry?.localReferenceKey ?: upload?.localReferenceKey.orEmpty(),
            uploadId = selection.uploadId,
            contentSha256 = selection.contentSha256,
            stages = selectedStageDecisions(invalidationReasons),
            blockers = blockers,
        )
    }

    private fun selectedStageDecisions(invalidationReasons: List<String>): List<SuccessorStageDecision> {
        val sourceInvalidated = invalidationReasons.any { it in SOURCE_INVALIDATIONS }
        val claimsInvalidated = invalidationReasons.any { it in CLAIM_INVALIDATIONS }
        val resolutionInvalidated = invalidationReasons.any { it in RESOLUTION_INVALIDATIONS }
        val evidenceInvalidated = invalidationReasons.any { it in EVIDENCE_INVALIDATIONS }
        return listOf(
            decision("source-parse", sourceInvalidated, "SOURCE_INPUTS_UNCHANGED", "SOURCE_CONFIGURATION_CHANGED"),
            decision("claim-extraction", claimsInvalidated, "CLAIM_OUTPUTS_REUSED", "CLAIM_CONFIGURATION_CHANGED"),
            decision("reference-resolution", resolutionInvalidated, "IDENTITY_OUTPUTS_REUSED", "RESOLUTION_CONFIGURATION_CHANGED"),
            SuccessorStageDecision("selected-access", SuccessorWorkDisposition.REPROCESS, "EXACT_USER_SELECTED_ASSET"),
            SuccessorStageDecision("cited-paper-parse", SuccessorWorkDisposition.REPROCESS, "SELECTED_ASSET_REPARSED_IN_SUCCESSOR"),
            decision("evidence-retrieval", evidenceInvalidated, "RETRIEVAL_CONFIGURATION_REUSED", "EVIDENCE_RETRIEVAL_CONFIGURATION_CHANGED"),
            SuccessorStageDecision("claim-verification", SuccessorWorkDisposition.REPROCESS, "SELECTED_ENTRY_ONLY"),
        )
    }

    private fun decision(stage: String, invalidated: Boolean, reusableReason: String, invalidReason: String) =
        SuccessorStageDecision(
            stage = stage,
            disposition = if (invalidated) SuccessorWorkDisposition.REPROCESS else SuccessorWorkDisposition.REUSE,
            reasonCode = if (invalidated) invalidReason else reusableReason,
        )

    private fun hasMatchingExternalProviderConsents(configuration: AnalysisConfigurationSnapshot): Boolean {
        val selections = listOfNotNull(
            configuration.claimExtractor,
            configuration.embedding,
            configuration.systemOne,
            configuration.sourceParser,
            configuration.languageDetector,
            configuration.referenceResolution.provider,
            configuration.openAccess,
            configuration.citedPaperParserSelection(),
        )
        val externalSelections = selections.filter { it.trustBoundary != "LOCAL" }
        if (externalSelections.any { it.trustBoundary != "EXTERNAL" }) return false
        val selectionsByProvider = externalSelections.groupBy(ProviderSelection::provider)
        val consentsByProvider = configuration.externalProviderConsents.groupBy { it.providerId }
        if (consentsByProvider.keys != selectionsByProvider.keys || consentsByProvider.values.any { it.size != 1 }) return false
        return selectionsByProvider.all { (providerId, providerSelections) ->
            val categories = providerSelections.flatMap(ProviderSelection::dataCategories).distinct().sorted()
            val disclosures = providerSelections.map(ProviderSelection::retentionDisclosure).distinct()
            val disclosure = disclosures.singleOrNull()
            val consent = consentsByProvider[providerId]?.singleOrNull()
            disclosure != null && !disclosure.isNullOrBlank() && consent != null &&
                consent.dataCategories == categories && consent.retentionDisclosure == disclosure
        }
    }

    private fun configurationInvalidations(
        predecessor: AnalysisConfigurationSnapshot,
        successor: AnalysisConfigurationSnapshot,
    ): List<String> = buildList {
        if (predecessor.sourceParser != successor.sourceParser ||
            predecessor.languageDetector != successor.languageDetector ||
            predecessor.validationLimits != successor.validationLimits
        ) add("SOURCE_OR_VALIDATION_POLICY_CHANGED")
        if (predecessor.claimExtractor != successor.claimExtractor ||
            predecessor.bibliographyNormalizationPolicy != successor.bibliographyNormalizationPolicy
        ) add("CLAIM_OR_BIBLIOGRAPHY_CONFIGURATION_CHANGED")
        if (predecessor.referenceResolution.copy(executionStatus = "") != successor.referenceResolution.copy(executionStatus = "")) {
            add("REFERENCE_RESOLUTION_CONFIGURATION_CHANGED")
        }
        if (predecessor.embedding != successor.embedding || predecessor.retrieval != successor.retrieval) {
            add("EVIDENCE_RETRIEVAL_CONFIGURATION_CHANGED")
        }
        if (predecessor.systemOne != successor.systemOne ||
            predecessor.aggregation.copy(executionStatus = "") != successor.aggregation.copy(executionStatus = "")
        ) add("VERIFICATION_CONFIGURATION_CHANGED")
        if (predecessor.citedPaperParserSelection() != successor.citedPaperParserSelection()) add("CITED_PAPER_PARSER_CONFIGURATION_CHANGED")
        if (predecessor.openAccess != successor.openAccess ||
            predecessor.openAccessProviderConfigurationFingerprint != successor.openAccessProviderConfigurationFingerprint ||
            predecessor.openAccessRetentionDisclosure != successor.openAccessRetentionDisclosure
        ) add("OPEN_ACCESS_CONFIGURATION_CHANGED")
        if (predecessor.captureExecution != successor.captureExecution) add("EXECUTION_CAPTURE_SETTING_CHANGED")
        if (predecessor.externalProviderConsents != successor.externalProviderConsents) add("EXTERNAL_PROVIDER_CONSENT_CHANGED")
        if (predecessor.copy(
                referenceResolution = predecessor.referenceResolution.copy(executionStatus = ""),
                aggregation = predecessor.aggregation.copy(executionStatus = ""),
            ) != successor.copy(
                referenceResolution = successor.referenceResolution.copy(executionStatus = ""),
                aggregation = successor.aggregation.copy(executionStatus = ""),
            ) && isEmpty()
        ) add("OTHER_RUN_CONFIGURATION_CHANGED")
    }

    private fun ProviderSelection.toExpectedWork(stageName: String) = SuccessorExpectedProviderWork(
        stage = stageName,
        providerId = provider,
        trustBoundary = trustBoundary,
        dataCategories = dataCategories,
        retentionDisclosure = retentionDisclosure,
    )

    private fun canonicalJson(node: JsonNode): String = when {
        node.isObject -> node.fieldNames().asSequence().toList().sorted().joinToString(",", "{", "}") { field ->
            "${JsonUtil.toJson(field)}:${canonicalJson(node.get(field))}"
        }
        node.isArray -> node.joinToString(",", "[", "]") { child -> canonicalJson(child) }
        else -> node.toString()
    }

    private data class SuccessorPlanFingerprintInput(
        val batchId: UUID,
        val predecessorAnalysisRunId: UUID,
        val revision: Long,
        val predecessorSourceSha256: String,
        val configuration: AnalysisConfigurationSnapshot,
        val selectedEntries: List<SuccessorSelectedEntryPlan>,
        val retainedReferenceKeys: List<String>,
        val invalidationReasons: List<String>,
        val blockers: List<String>,
    )

    private companion object {
        val COMPLETED_RUN_STATUSES = setOf("COMPLETED", "COMPLETED_WITH_WARNINGS")
        val SOURCE_INVALIDATIONS = setOf("SOURCE_OR_VALIDATION_POLICY_CHANGED")
        val CLAIM_INVALIDATIONS = setOf("CLAIM_OR_BIBLIOGRAPHY_CONFIGURATION_CHANGED", "SOURCE_OR_VALIDATION_POLICY_CHANGED")
        val RESOLUTION_INVALIDATIONS = setOf("CLAIM_OR_BIBLIOGRAPHY_CONFIGURATION_CHANGED", "REFERENCE_RESOLUTION_CONFIGURATION_CHANGED")
        val EVIDENCE_INVALIDATIONS = setOf("EVIDENCE_RETRIEVAL_CONFIGURATION_CHANGED")
    }
}
