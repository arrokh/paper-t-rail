package com.papertrail.api.analysis.successor.service

import com.papertrail.api.analysis.repository.AnalysisRunPipelineProgressRepository
import com.papertrail.api.analysis.recovery.repository.RecoveryUploadCleanupRepository
import com.papertrail.api.analysis.successor.repository.SelectedSuccessorAsset
import com.papertrail.api.analysis.successor.repository.SuccessorSelectedAssetRepository
import com.papertrail.api.evidence.queue.CitedPaperIndexingQueue
import com.papertrail.api.evidence.queue.EvidenceIndexingEnqueueResult
import com.papertrail.api.scholarly.acquisition.domain.UserSuppliedCitedPaperProvenance
import com.papertrail.api.scholarly.acquisition.repository.CitedPaperAccessRepository
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID

@Service
class SuccessorSelectedAssetService(
    private val repository: SuccessorSelectedAssetRepository,
    private val citedPaperAccessRepository: CitedPaperAccessRepository,
    private val cleanupRepository: RecoveryUploadCleanupRepository,
    private val pipelineProgressRepository: AnalysisRunPipelineProgressRepository,
    private val citedPaperIndexingQueue: CitedPaperIndexingQueue,
) {
    fun handoffIfSelected(
        analysisRunId: UUID,
        bibliographyEntryId: UUID,
        identityResolved: Boolean,
        documentId: UUID,
        sourceContentSha256: String,
        correlationId: UUID,
        causationId: UUID,
        traceparent: String?,
        tracestate: String?,
    ): HandoffResult {
        val asset = repository.selectedAsset(analysisRunId, bibliographyEntryId) ?: return HandoffResult.NOT_SELECTED
        if (!identityResolved) {
            cleanupRepository.scheduleSubmittedUnownedUpload(analysisRunId, bibliographyEntryId, Instant.now())
            return HandoffResult.IDENTITY_UNRESOLVED
        }
        validateSelectedAsset(asset)

        val reference = citedPaperAccessRepository.resolvedReference(analysisRunId, bibliographyEntryId)
            ?: error("Resolved successor Bibliography Entry is missing its Canonical Paper identity.")
        val provenance = UserSuppliedCitedPaperProvenance(
            contentSha256 = requireNotNull(asset.selectedContentSha256),
            mediaType = PDF_MEDIA_TYPE,
            language = requireNotNull(asset.detectedLanguage),
            languageDetectorVersion = requireNotNull(asset.languageDetectorVersion),
            rightsDeclarationVersion = requireNotNull(asset.rightsDeclarationVersion),
            rightsDeclarationText = requireNotNull(asset.rightsDeclarationText),
            rightsDeclaredAt = asset.rightsDeclaredAt,
            originatingAnalysisRunId = asset.originatingAnalysisRunId,
            originatingRecoveryBatchId = asset.batchId,
            originatingRecoveryUploadId = asset.uploadId,
        )
        check(
            citedPaperAccessRepository.saveVerifiedUserSuppliedAssetReference(
                analysisRunId = analysisRunId,
                reference = reference,
                objectKey = requireNotNull(asset.objectKey),
                provenance = provenance,
                discoveredAt = Instant.now(),
            ),
        ) { "Selected user-supplied access already exists in the fresh successor run." }

        val now = Instant.now()
        repository.ensureSelectedVerifications(
            analysisRunId = analysisRunId,
            bibliographyEntryId = bibliographyEntryId,
            canonicalPaperId = reference.canonicalPaperId,
            now = now,
        )
        check(repository.insertOwnership(asset)) { "Exact selected asset ownership could not be recorded." }
        pipelineProgressRepository.markBibliographyItem(
            analysisRunId,
            "access",
            "acquire-source",
            bibliographyEntryId,
            "COMPLETED",
            "USER_SUPPLIED_ASSET_SELECTED",
        )
        val queued = citedPaperIndexingQueue.enqueueIfEligible(
            analysisRunId = analysisRunId,
            bibliographyEntryId = bibliographyEntryId,
            documentId = documentId,
            sourceContentSha256 = sourceContentSha256,
            correlationId = correlationId,
            causationId = causationId,
            traceparent = traceparent,
            tracestate = tracestate,
        )
        check(queued == EvidenceIndexingEnqueueResult.QUEUED) {
            "Selected successor indexing work could not be queued."
        }
        return HandoffResult.QUEUED
    }

    fun isIdentityOnly(analysisRunId: UUID, bibliographyEntryId: UUID): Boolean =
        repository.isIdentityOnly(analysisRunId, bibliographyEntryId)

    fun scheduleCleanupIfUnowned(analysisRunId: UUID, bibliographyEntryId: UUID, now: Instant = Instant.now()): Boolean =
        cleanupRepository.scheduleSubmittedUnownedUpload(analysisRunId, bibliographyEntryId, now)

    private fun validateSelectedAsset(asset: SelectedSuccessorAsset) {
        check(asset.batchStatus == "SUBMITTED") { "Selected successor asset is not protected by a submitted Recovery Batch." }
        check(asset.uploadStatus == "STAGED") { "Selected Recovery Upload is not staged." }
        check(!asset.objectKey.isNullOrBlank()) { "Selected Recovery Upload object key is missing." }
        val contentSha256 = requireNotNull(asset.selectedContentSha256)
        check(contentSha256 == asset.actualSha256 && contentSha256 == asset.selectionContentSha256) {
            "Selected Recovery Upload hash no longer matches its submission snapshot."
        }
        check(asset.selectionMethod == "HUMAN_CONFIRMED") {
            "Unresolved-identity asset handoff requires human confirmation of the exact PDF version."
        }
        check(asset.validationAttemptId != null && asset.validationStatus == "COMPLETED") {
            "Selected Recovery Upload validation attempt is missing or incomplete."
        }
        check(asset.identityOutcome == "NEEDS_CONFIRMATION") {
            "Selected Recovery Upload does not have a human-confirmed unresolved identity decision."
        }
        check(asset.attemptContentSha256 == contentSha256) {
            "Selected Recovery Upload validation hash does not match its exact asset."
        }
        check(asset.languageEligibility == "ELIGIBLE" && asset.detectedLanguage.equals("en", ignoreCase = true)) {
            "Selected Recovery Upload is not eligible English full text."
        }
        check(!asset.languageDetectorVersion.isNullOrBlank()) {
            "Selected Recovery Upload language-detector provenance is missing."
        }
        check(!asset.rightsDeclarationVersion.isNullOrBlank() && !asset.rightsDeclarationText.isNullOrBlank()) {
            "Selected Recovery Upload rights declaration provenance is missing."
        }
        check(!asset.assistedDoi.isNullOrBlank() && asset.assistanceSourceCode == HUMAN_CONFIRMED_PDF_DOI_CANDIDATE) {
            "Selected Recovery Upload has no human-confirmed DOI assistance for this successor resolution."
        }
        check(asset.predecessorAnalysisRunId == asset.originatingAnalysisRunId) {
            "Selected Recovery Upload provenance does not match the predecessor Analysis Run."
        }
    }

    enum class HandoffResult {
        NOT_SELECTED,
        IDENTITY_UNRESOLVED,
        QUEUED,
    }

    private companion object {
        const val PDF_MEDIA_TYPE = "application/pdf"
        const val HUMAN_CONFIRMED_PDF_DOI_CANDIDATE = "HUMAN_CONFIRMED_PDF_DOI_CANDIDATE"
    }
}
