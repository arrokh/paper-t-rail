package com.papertrail.api.analysis.successor.service

import com.papertrail.api.analysis.configuration.RunConfigurationFactory
import com.papertrail.api.analysis.http.RunConfigurationRequest
import com.papertrail.api.analysis.recovery.domain.RecoveryAssetSelectionMethod
import com.papertrail.api.analysis.recovery.domain.RecoveryBatch
import com.papertrail.api.analysis.recovery.domain.RecoveryBatchStatus
import com.papertrail.api.analysis.recovery.domain.RecoveryIdentityOutcome
import com.papertrail.api.analysis.recovery.domain.RecoveryLanguageEligibility
import com.papertrail.api.analysis.recovery.domain.RecoveryRightsDeclaration
import com.papertrail.api.analysis.recovery.domain.RecoveryStagingException
import com.papertrail.api.analysis.recovery.domain.RecoveryUpload
import com.papertrail.api.analysis.recovery.domain.RecoveryUploadAssetSelection
import com.papertrail.api.analysis.recovery.domain.RecoveryUploadStatus
import com.papertrail.api.analysis.recovery.domain.RecoveryUploadValidationAttempt
import com.papertrail.api.analysis.recovery.domain.RecoveryValidationStatus
import com.papertrail.api.analysis.recovery.repository.RecoveryBatchRepository
import com.papertrail.api.analysis.recovery.repository.RecoveryUploadCleanupRepository
import com.papertrail.api.analysis.recovery.service.RecoveryUploadValidationService
import com.papertrail.api.analysis.successor.domain.SuccessorRunPlan
import com.papertrail.api.analysis.successor.domain.SuccessorSelectedEntryPlan
import com.papertrail.api.analysis.successor.domain.SuccessorStageDecision
import com.papertrail.api.analysis.successor.domain.SuccessorWorkDisposition
import com.papertrail.api.analysis.successor.http.SuccessorRunPreviewRequest
import com.papertrail.api.analysis.successor.http.SuccessorRunSubmissionRequest
import com.papertrail.api.analysis.successor.repository.PersistedSuccessorSubmission
import com.papertrail.api.analysis.successor.repository.SubmissionContext
import com.papertrail.api.analysis.successor.repository.SuccessorRunSubmissionRepository
import com.papertrail.api.document.domain.SourceDocumentDeletedException
import com.papertrail.api.document.repository.SourceDocumentRepository
import com.papertrail.api.infrastructure.storage.SourceDocumentObjectStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.SimpleTransactionStatus
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.util.UUID

class SuccessorRunSubmissionServiceTest {
    @Test
    fun `deletion after asset verification but before transaction locks prevents successor materialization`() {
        val now = Instant.now()
        val batchId = UUID.randomUUID()
        val predecessorRunId = UUID.randomUUID()
        val documentId = UUID.randomUUID()
        val entryId = UUID.randomUUID()
        val uploadId = UUID.randomUUID()
        val validationId = UUID.randomUUID()
        val requestId = UUID.randomUUID()
        val bytes = byteArrayOf(1, 2, 3, 4)
        val hash = com.papertrail.api.infrastructure.crypto.sha256Hex(bytes)
        val selection = RecoveryUploadAssetSelection(
            id = UUID.randomUUID(), batchId = batchId, analysisRunId = predecessorRunId,
            bibliographyEntryId = entryId, uploadId = uploadId, validationAttemptId = validationId,
            contentSha256 = hash, selectionMethod = RecoveryAssetSelectionMethod.MACHINE_VALIDATED, selectedAt = now,
        )
        val upload = RecoveryUpload(
            id = uploadId, batchId = batchId, analysisRunId = predecessorRunId, bibliographyEntryId = entryId,
            localReferenceKey = "ref1", idempotencyKey = UUID.randomUUID(), filename = "selected.pdf",
            expectedSize = bytes.size.toLong(), expectedSha256 = hash, stagingObjectKey = "staging/$uploadId",
            finalizedObjectKey = "finalized/$uploadId", status = RecoveryUploadStatus.STAGED, failureCode = null,
            presignedUrlExpiresAt = now.plusSeconds(300), actualSize = bytes.size.toLong(), actualSha256 = hash,
            createdAt = now, updatedAt = now, finalizedAt = now,
        )
        val batch = RecoveryBatch(
            id = batchId, analysisRunId = predecessorRunId, status = RecoveryBatchStatus.OPEN,
            rightsDeclaration = RecoveryRightsDeclaration.CURRENT, rightsDeclaredAt = now,
            createdAt = now, lastActivityAt = now, expiresAt = now.plusSeconds(3600), revision = 3,
            uploads = listOf(upload), selectedAssets = listOf(selection),
        )
        val attempt = RecoveryUploadValidationAttempt(
            id = validationId, batchId = batchId, uploadId = uploadId, analysisRunId = predecessorRunId,
            contentSha256 = hash, parserId = "docling", parserVersion = "1.30.0",
            metadataExtractionPolicyVersion = "metadata-v2", identityPolicyVersion = "identity-v2",
            parserOptions = emptyMap(), languageDetectorId = "optimaize", languageDetectorVersion = "0.6",
            minimumLanguageConfidence = 0.65, minimumExtractedCharacters = 100,
            languagePolicyFingerprint = "b".repeat(64), validationStatus = RecoveryValidationStatus.COMPLETED,
            identityOutcome = RecoveryIdentityOutcome.VALIDATED, identityReasonCode = "DOI_MATCH",
            metadataCandidates = emptyList(), languageEligibility = RecoveryLanguageEligibility.ELIGIBLE,
            detectedLanguage = "en", languageConfidence = 0.99, languageReasonCode = "ENGLISH_DETECTED",
            failureCode = null, createdAt = now,
        )
        val selectedPlan = SuccessorSelectedEntryPlan(
            predecessorBibliographyEntryId = entryId, localReferenceKey = "ref1", uploadId = uploadId,
            contentSha256 = hash,
            assistedDoi = null,
            stages = listOf(SuccessorStageDecision("selected-access", SuccessorWorkDisposition.REPROCESS, "EXACT_USER_SELECTED_ASSET")),
            blockers = emptyList(),
        )
        val plan = SuccessorRunPlan(
            batchId = batchId, predecessorAnalysisRunId = predecessorRunId, revision = batch.revision,
            planFingerprint = "c".repeat(64), eligible = true, selectedEntries = listOf(selectedPlan),
            identifierAssistanceEntries = emptyList(),
            retainedReferenceKeys = emptyList(),
            retainedDecision = SuccessorStageDecision("unselected-bibliography-coverage", SuccessorWorkDisposition.RETAIN, "UNSELECTED_COVERAGE_PRESERVED"),
            retainedStageDecisions = emptyList(),
            invalidationReasons = emptyList(), blockers = emptyList(), expectedProviderWork = emptyList(), requiredConsents = emptyList(),
        )
        val previewService = Mockito.mock(SuccessorRunService::class.java)
        val previewRequest = SuccessorRunPreviewRequest(3, RunConfigurationRequest())
        `when`(previewService.preview(batchId, previewRequest)).thenReturn(plan)
        val batchRepository = Mockito.mock(RecoveryBatchRepository::class.java)
        `when`(batchRepository.activeBatchById(anyUuid(), anyInstant())).thenReturn(batch)
        val validationService = Mockito.mock(RecoveryUploadValidationService::class.java)
        `when`(validationService.latest(batchId, uploadId)).thenReturn(attempt)
        val sourceDocumentRepository = Mockito.mock(SourceDocumentRepository::class.java)
        doThrow(SourceDocumentDeletedException()).`when`(sourceDocumentRepository).lockActiveAnalysisRun(predecessorRunId)
        val submissionRepository = Mockito.mock(SuccessorRunSubmissionRepository::class.java)
        `when`(submissionRepository.existingSubmission(batchId)).thenReturn(null)
        `when`(submissionRepository.submissionContext(batchId)).thenReturn(SubmissionContext(predecessorRunId, documentId))
        val objectStore = Mockito.mock(SourceDocumentObjectStore::class.java)
        `when`(objectStore.get(upload.finalizedObjectKey)).thenReturn(bytes)
        val transactionManager = Mockito.mock(PlatformTransactionManager::class.java)
        `when`(transactionManager.getTransaction(Mockito.any(TransactionDefinition::class.java))).thenReturn(SimpleTransactionStatus())
        val service = SuccessorRunSubmissionService(
            successorRunService = previewService,
            recoveryBatchRepository = batchRepository,
            recoveryUploadValidationService = validationService,
            sourceDocumentRepository = sourceDocumentRepository,
            submissionRepository = submissionRepository,
            objectStore = objectStore,
            transactionTemplate = TransactionTemplate(transactionManager),
            configurationFactory = Mockito.mock(RunConfigurationFactory::class.java),
            recoveryUploadCleanupRepository = Mockito.mock(RecoveryUploadCleanupRepository::class.java),
        )

        val failure = assertThrows(RecoveryStagingException::class.java) {
            service.submit(batchId, SuccessorRunSubmissionRequest(requestId, batch.revision))
        }

        assertEquals("ANALYSIS_RUN_NOT_FOUND", failure.code)
        verify(objectStore).get(upload.finalizedObjectKey)
        verify(sourceDocumentRepository).lockActiveAnalysisRun(predecessorRunId)
        verify(submissionRepository, Mockito.never()).lockPredecessor(predecessorRunId)
        verify(submissionRepository, Mockito.never()).lockBatch(batchId)
    }

    @Test
    fun `idempotent replay checks source deletion before returning the stored successor`() {
        val batchId = UUID.randomUUID()
        val predecessorRunId = UUID.randomUUID()
        val idempotencyKey = UUID.randomUUID()
        val request = SuccessorRunSubmissionRequest(idempotencyKey, expectedRevision = 3, configuration = RunConfigurationRequest())
        val existing = PersistedSuccessorSubmission(
            id = UUID.randomUUID(),
            batchId = batchId,
            predecessorAnalysisRunId = predecessorRunId,
            successorAnalysisRunId = UUID.randomUUID(),
            idempotencyKey = idempotencyKey,
            requestFingerprint = "a".repeat(64),
            planFingerprint = "b".repeat(64),
            revision = 3,
            selectedEntryCount = 1,
            retainedEntryCount = 0,
            queuedEntryCount = 1,
        )
        val submissionRepository = Mockito.mock(SuccessorRunSubmissionRepository::class.java)
        `when`(submissionRepository.existingSubmission(batchId)).thenReturn(existing)
        val sourceDocumentRepository = Mockito.mock(SourceDocumentRepository::class.java)
        doThrow(SourceDocumentDeletedException()).`when`(sourceDocumentRepository).lockActiveAnalysisRun(predecessorRunId)
        val transactionManager = Mockito.mock(PlatformTransactionManager::class.java)
        `when`(transactionManager.getTransaction(Mockito.any(TransactionDefinition::class.java))).thenReturn(SimpleTransactionStatus())
        val service = SuccessorRunSubmissionService(
            successorRunService = Mockito.mock(SuccessorRunService::class.java),
            recoveryBatchRepository = Mockito.mock(RecoveryBatchRepository::class.java),
            recoveryUploadValidationService = Mockito.mock(RecoveryUploadValidationService::class.java),
            sourceDocumentRepository = sourceDocumentRepository,
            submissionRepository = submissionRepository,
            objectStore = Mockito.mock(SourceDocumentObjectStore::class.java),
            transactionTemplate = TransactionTemplate(transactionManager),
            configurationFactory = Mockito.mock(RunConfigurationFactory::class.java),
            recoveryUploadCleanupRepository = Mockito.mock(RecoveryUploadCleanupRepository::class.java),
        )

        val failure = assertThrows(RecoveryStagingException::class.java) { service.submit(batchId, request) }

        assertEquals("ANALYSIS_RUN_NOT_FOUND", failure.code)
        verify(sourceDocumentRepository).lockActiveAnalysisRun(predecessorRunId)
        verify(submissionRepository, Mockito.times(1)).existingSubmission(batchId)
    }

    private fun anyInstant(): Instant = Mockito.any(Instant::class.java) ?: Instant.EPOCH
    private fun anyUuid(): UUID = Mockito.any(UUID::class.java) ?: UUID(0L, 0L)
}
