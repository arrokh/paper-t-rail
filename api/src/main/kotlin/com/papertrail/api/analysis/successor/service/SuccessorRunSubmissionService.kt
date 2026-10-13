package com.papertrail.api.analysis.successor.service

import com.fasterxml.jackson.databind.JsonNode
import com.papertrail.api.analysis.configuration.RunConfigurationFactory
import com.papertrail.api.analysis.recovery.domain.RecoveryStagingException
import com.papertrail.api.analysis.recovery.repository.RecoveryBatchRepository
import com.papertrail.api.analysis.recovery.service.RecoveryUploadValidationService
import com.papertrail.api.analysis.successor.http.SuccessorRunPreviewRequest
import com.papertrail.api.analysis.successor.http.SuccessorRunSubmissionRequest
import com.papertrail.api.analysis.successor.repository.PersistedSuccessorSubmission
import com.papertrail.api.analysis.successor.repository.SuccessorRunSubmissionRepository
import com.papertrail.api.analysis.successor.repository.VerifiedSuccessorUpload
import com.papertrail.api.document.domain.SourceDocumentDeletedException
import com.papertrail.api.document.repository.SourceDocumentRepository
import com.papertrail.api.infrastructure.crypto.sha256Hex
import com.papertrail.api.infrastructure.storage.SourceDocumentObjectStore
import com.papertrail.api.utils.JsonUtil
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID

@Service
class SuccessorRunSubmissionService(
    private val successorRunService: SuccessorRunService,
    private val recoveryBatchRepository: RecoveryBatchRepository,
    private val recoveryUploadValidationService: RecoveryUploadValidationService,
    private val sourceDocumentRepository: SourceDocumentRepository,
    private val submissionRepository: SuccessorRunSubmissionRepository,
    private val objectStore: SourceDocumentObjectStore,
    private val transactionTemplate: TransactionTemplate,
    private val configurationFactory: RunConfigurationFactory,
) {
    fun submit(batchId: UUID, request: SuccessorRunSubmissionRequest): SuccessorSubmissionResult {
        val requestFingerprint = requestFingerprint(request)
        submissionRepository.existingSubmission(batchId)?.let {
            assertReplayOrConflict(it, request, requestFingerprint)
            return it.replay().toResult()
        }

        val previewRequest = SuccessorRunPreviewRequest(request.expectedRevision, request.configuration)
        val advisoryPlan = successorRunService.preview(batchId, previewRequest)
        ensureEligible(advisoryPlan.eligible, advisoryPlan.blockers)
        val currentBatch = recoveryBatchRepository.activeBatchById(batchId, Instant.now())
        val verifiedUploads = advisoryPlan.selectedEntries.map { selectedPlan ->
            val selection = currentBatch.selectedAssets.singleOrNull {
                it.bibliographyEntryId == selectedPlan.predecessorBibliographyEntryId && it.uploadId == selectedPlan.uploadId
            } ?: throw RecoveryStagingException("SUCCESSOR_SELECTION_CHANGED", 409, "The current selected asset changed. Refresh the Recovery Batch before submitting.")
            val upload = currentBatch.uploads.singleOrNull { it.id == selection.uploadId }
                ?: throw RecoveryStagingException("SELECTED_UPLOAD_NOT_FOUND", 409, "The selected Recovery Upload is no longer available.")
            val latestAttempt = recoveryUploadValidationService.latest(batchId, upload.id)
                ?.takeIf { it.id == selection.validationAttemptId }
                ?: throw RecoveryStagingException("CURRENT_UPLOAD_VALIDATION_REQUIRED", 409, "The selected asset requires a current successful validation.")
            val bytes = readAndVerifySelectedAsset(upload.finalizedObjectKey, upload.actualSize, selectedPlan.contentSha256)
            VerifiedSuccessorUpload(selectedPlan.predecessorBibliographyEntryId, upload, bytes, latestAttempt)
        }

        val context = submissionRepository.submissionContext(batchId)
            ?: throw RecoveryStagingException("RECOVERY_BATCH_NOT_FOUND", 404, "Recovery Batch not found.")
        val persisted = transactionTemplate.execute {
            try {
                sourceDocumentRepository.lockActiveAnalysisRun(context.predecessorAnalysisRunId)
            } catch (_: SourceDocumentDeletedException) {
                throw RecoveryStagingException("ANALYSIS_RUN_NOT_FOUND", 404, "The predecessor Analysis Run or its Source Document was deleted.")
            }
            val predecessor = submissionRepository.lockPredecessor(context.predecessorAnalysisRunId)
                ?: throw RecoveryStagingException("ANALYSIS_RUN_NOT_FOUND", 404, "Analysis Run not found.")
            if (predecessor.documentId != context.documentId) {
                throw RecoveryStagingException("ANALYSIS_RUN_NOT_FOUND", 404, "Analysis Run not found.")
            }
            val lockedBatch = submissionRepository.lockBatch(batchId)
                ?: throw RecoveryStagingException("RECOVERY_BATCH_NOT_FOUND", 404, "Recovery Batch not found.")

            submissionRepository.existingSubmission(batchId)?.let {
                assertReplayOrConflict(it, request, requestFingerprint)
                return@execute it.replay()
            }

            if (lockedBatch.status != "OPEN" || lockedBatch.expiresAt <= Instant.now()) {
                throw RecoveryStagingException("RECOVERY_BATCH_EXPIRED", 410, "This Recovery Batch has expired or has already been submitted.")
            }
            if (lockedBatch.revision != request.expectedRevision) {
                throw RecoveryStagingException("RECOVERY_BATCH_REVISION_STALE", 409, "Recovery Batch changed; refresh its current selections before submitting.")
            }
            if (predecessor.status !in setOf("COMPLETED", "COMPLETED_WITH_WARNINGS")) {
                throw RecoveryStagingException("PREDECESSOR_RUN_NOT_COMPLETED", 409, "Only a completed predecessor Analysis Run can be submitted for recovery.")
            }

            submissionRepository.lockSelectedUploads(batchId)
            val lockedBatchView = recoveryBatchRepository.activeBatchById(batchId, Instant.now())
            if (lockedBatchView.revision != request.expectedRevision) {
                throw RecoveryStagingException("RECOVERY_BATCH_REVISION_STALE", 409, "Recovery Batch changed; refresh its current selections before submitting.")
            }
            val finalPlan = successorRunService.preview(batchId, previewRequest)
            ensureEligible(finalPlan.eligible, finalPlan.blockers)
            if (finalPlan.planFingerprint != advisoryPlan.planFingerprint) {
                throw RecoveryStagingException("SUCCESSOR_PLAN_CHANGED", 409, "Successor inputs changed while the selected asset was being verified. Preview again before submitting.")
            }
            val finalSelectedAssets = finalPlan.selectedEntries.map { finalSelected ->
                val verified = verifiedUploads.singleOrNull { it.predecessorBibliographyEntryId == finalSelected.predecessorBibliographyEntryId }
                    ?: throw RecoveryStagingException("SUCCESSOR_SELECTION_CHANGED", 409, "The current selected asset changed. Refresh the Recovery Batch before submitting.")
                if (finalSelected.uploadId != verified.upload.id || finalSelected.contentSha256 != sha256Hex(verified.bytes)) {
                    throw RecoveryStagingException("SUCCESSOR_ASSET_CHANGED", 409, "A selected asset no longer matches its verified Recovery Upload.")
                }
                val finalSelection = lockedBatchView.selectedAssets.singleOrNull {
                    it.bibliographyEntryId == finalSelected.predecessorBibliographyEntryId && it.uploadId == verified.upload.id
                } ?: throw RecoveryStagingException("SUCCESSOR_SELECTION_CHANGED", 409, "A selected asset changed while submitting.")
                val finalAttempt = recoveryUploadValidationService.latest(batchId, verified.upload.id)
                    ?.takeIf { it.id == finalSelection.validationAttemptId }
                    ?: throw RecoveryStagingException("CURRENT_UPLOAD_VALIDATION_REQUIRED", 409, "A selected asset requires a current successful validation.")
                verified.copy(validationAttempt = finalAttempt)
            }
            if (finalSelectedAssets.size != verifiedUploads.size) {
                throw RecoveryStagingException("SUCCESSOR_SELECTION_CHANGED", 409, "The current selected asset set changed while submitting.")
            }

            val configuration = try {
                configurationFactory.from(request.configuration)
            } catch (_: IllegalArgumentException) {
                throw RecoveryStagingException("SUCCESSOR_CONFIGURATION_INVALID", 400, "The successor configuration or its required provider consent is invalid.")
            }
            val configurationJson = configurationFactory.toJson(configuration)
            val submissionId = UUID.randomUUID()
            val successorRunId = UUID.randomUUID()
            submissionRepository.materialize(
                submissionId = submissionId,
                successorRunId = successorRunId,
                batch = lockedBatchView,
                predecessor = predecessor,
                plan = finalPlan,
                configuration = configuration,
                configurationJson = configurationJson,
                requestFingerprint = requestFingerprint,
                idempotencyKey = request.idempotencyKey,
                selectedUploads = finalSelectedAssets,
                now = Instant.now(),
            )
        } ?: throw IllegalStateException("Successor submission transaction returned no result.")

        return persisted.toResult()
    }

    private fun readAndVerifySelectedAsset(objectKey: String, expectedSize: Long?, expectedHash: String): ByteArray {
        val bytes = try {
            objectStore.get(objectKey)
        } catch (_: Exception) {
            throw RecoveryStagingException("SELECTED_ASSET_UNAVAILABLE", 503, "Selected finalized asset could not be verified.")
        }
        if ((expectedSize != null && bytes.size.toLong() != expectedSize) || sha256Hex(bytes) != expectedHash) {
            throw RecoveryStagingException("SELECTED_ASSET_HASH_MISMATCH", 409, "Selected finalized asset no longer matches its recorded size and SHA-256.")
        }
        return bytes
    }

    private fun ensureEligible(eligible: Boolean, blockers: List<String>) {
        if (!eligible) {
            val reason = blockers.joinToString(", ").take(300)
            throw RecoveryStagingException("SUCCESSOR_PLAN_BLOCKED", 409, "Successor submission is blocked: $reason")
        }
    }

    private fun assertReplayOrConflict(
        existing: PersistedSuccessorSubmission,
        request: SuccessorRunSubmissionRequest,
        requestFingerprint: String,
    ) {
        if (existing.idempotencyKey != request.idempotencyKey) {
            throw RecoveryStagingException("SUCCESSOR_ALREADY_SUBMITTED", 409, "This Recovery Batch already has a successor submission.")
        }
        if (existing.requestFingerprint != requestFingerprint) {
            throw RecoveryStagingException("IDEMPOTENCY_KEY_CONFLICT", 409, "The idempotency key was already used with a different submission request.")
        }
    }

    private fun requestFingerprint(request: SuccessorRunSubmissionRequest): String {
        val normalized = JsonUtil.objectNode().apply {
            put("expectedRevision", request.expectedRevision)
            set<JsonNode>("configuration", JsonUtil.toTree(request.configuration))
        }
        return sha256Hex(canonicalJson(normalized).toByteArray(StandardCharsets.UTF_8))
    }

    private fun canonicalJson(node: JsonNode): String = when {
        node.isObject -> node.fieldNames().asSequence().toList().sorted().joinToString(",", "{", "}") { field ->
            "${JsonUtil.toJson(field)}:${canonicalJson(node.get(field))}"
        }
        node.isArray -> node.joinToString(",", "[", "]") { child -> canonicalJson(child) }
        else -> node.toString()
    }
}

data class SuccessorSubmissionResult(
    val submissionId: UUID,
    val successorAnalysisRunId: UUID,
    val predecessorAnalysisRunId: UUID,
    val revision: Long,
    val planFingerprint: String,
    val selectedEntryCount: Int,
    val retainedEntryCount: Int,
    val queuedEntryCount: Int,
    val replayed: Boolean,
)

data class VerifiedSuccessorUpload(
    val predecessorBibliographyEntryId: UUID,
    val upload: com.papertrail.api.analysis.recovery.domain.RecoveryUpload,
    val bytes: ByteArray,
    val validationAttempt: com.papertrail.api.analysis.recovery.domain.RecoveryUploadValidationAttempt,
)

private fun PersistedSuccessorSubmission.toResult() = SuccessorSubmissionResult(
    submissionId = id,
    successorAnalysisRunId = successorAnalysisRunId,
    predecessorAnalysisRunId = predecessorAnalysisRunId,
    revision = revision,
    planFingerprint = planFingerprint,
    selectedEntryCount = selectedEntryCount,
    retainedEntryCount = retainedEntryCount,
    queuedEntryCount = queuedEntryCount,
    replayed = replayed,
)
