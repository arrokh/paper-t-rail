package com.papertrail.api.analysis.recovery.service

import com.papertrail.api.analysis.recovery.domain.RecoveryAssetSelectionMethod
import com.papertrail.api.analysis.recovery.domain.RecoveryIdentityConfirmation
import com.papertrail.api.analysis.recovery.domain.RecoveryIdentityOutcome
import com.papertrail.api.analysis.recovery.domain.RecoveryLanguageEligibility
import com.papertrail.api.analysis.recovery.domain.RecoveryStagingException
import com.papertrail.api.analysis.recovery.domain.RecoveryUploadAssetSelection
import com.papertrail.api.analysis.recovery.domain.RecoveryUploadIdentifierConfirmation
import com.papertrail.api.analysis.recovery.domain.RecoveryUploadMetadataCandidateFingerprint
import com.papertrail.api.analysis.recovery.domain.RecoveryUploadIdentityPolicy
import com.papertrail.api.analysis.recovery.domain.RecoveryUploadValidationAttempt
import com.papertrail.api.analysis.recovery.domain.RecoveryUploadValidationContext
import com.papertrail.api.analysis.recovery.domain.RecoveryValidationStatus
import com.papertrail.api.analysis.recovery.domain.languageEligibilityPolicy
import com.papertrail.api.citation.parsing.ParsedBibliographicMetadataField
import com.papertrail.api.scholarly.references.normalization.DoiNormalizer
import com.papertrail.api.analysis.recovery.repository.RecoveryUploadIdentityDecisionRepository
import com.papertrail.api.analysis.recovery.repository.RecoveryUploadValidationRepository
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID

@Service
class RecoveryUploadIdentityService(
    private val validationRepository: RecoveryUploadValidationRepository,
    private val decisionRepository: RecoveryUploadIdentityDecisionRepository,
) {
    fun confirmExactVersion(
        batchId: UUID,
        uploadId: UUID,
        validationAttemptId: UUID,
        confirmExactVersion: Boolean,
        confirmedDoi: String? = null,
        doiCandidateFingerprint: String? = null,
    ): RecoveryIdentityConfirmation {
        if (!confirmExactVersion) {
            throw RecoveryStagingException("RECOVERY_IDENTITY_CONFIRMATION_REQUIRED", 400, "Explicitly confirm the exact PDF version before continuing.")
        }
        val context = validationContext(batchId, uploadId)
        val attempt = latestAttempt(batchId, uploadId, context)
        if (attempt.id != validationAttemptId) {
            throw RecoveryStagingException("RECOVERY_VALIDATION_ATTEMPT_STALE", 409, "Run validation again before confirming this PDF version.")
        }
        if (attempt.validationStatus != RecoveryValidationStatus.COMPLETED ||
            attempt.identityOutcome != RecoveryIdentityOutcome.NEEDS_CONFIRMATION
        ) {
            throw RecoveryStagingException("RECOVERY_IDENTITY_NOT_CONFIRMABLE", 409, "Only an inconclusive identity result can be confirmed by a person.")
        }
        val actualSha = context.upload.actualSha256
            ?: throw RecoveryStagingException("RECOVERY_UPLOAD_NOT_STAGED", 409, "The finalized PDF checksum is unavailable.")
        if (attempt.contentSha256 != actualSha) {
            throw RecoveryStagingException("RECOVERY_VALIDATION_STALE", 409, "The validated PDF bytes no longer match the staged snapshot.")
        }
        if ((confirmedDoi == null) != (doiCandidateFingerprint == null)) {
            throw RecoveryStagingException(
                "RECOVERY_DOI_CONFIRMATION_INCOMPLETE",
                400,
                "Confirm both a normalized DOI and its metadata-candidate fingerprint, or provide neither.",
            )
        }
        val identifierConfirmation = if (confirmedDoi != null && doiCandidateFingerprint != null) {
            val normalizedDoi = DoiNormalizer.normalize(confirmedDoi)
            if (normalizedDoi == null || normalizedDoi != confirmedDoi) {
                throw RecoveryStagingException("RECOVERY_DOI_CONFIRMATION_INVALID", 400, "Provide the canonical lower-case DOI shown for the selected metadata candidate.")
            }
            val matchingCandidates = attempt.metadataCandidates.filter { candidate ->
                candidate.field == ParsedBibliographicMetadataField.DOI &&
                    RecoveryUploadMetadataCandidateFingerprint.of(candidate) == doiCandidateFingerprint &&
                    DoiNormalizer.normalize(candidate.value) == normalizedDoi
            }
            if (matchingCandidates.size != 1) {
                throw RecoveryStagingException(
                    "RECOVERY_DOI_CANDIDATE_NOT_FOUND",
                    409,
                    "Choose one DOI candidate and source location from this exact validation attempt.",
                )
            }
            RecoveryUploadIdentifierConfirmation(
                id = UUID.randomUUID(),
                batchId = batchId,
                uploadId = uploadId,
                analysisRunId = context.upload.analysisRunId,
                validationAttemptId = attempt.id,
                contentSha256 = actualSha,
                identifierType = DOI_IDENTIFIER_TYPE,
                identifierValue = normalizedDoi,
                candidateFingerprint = doiCandidateFingerprint,
                confirmedAt = Instant.now(),
            )
        } else {
            null
        }
        return decisionRepository.recordConfirmation(
            RecoveryIdentityConfirmation(
                id = UUID.randomUUID(),
                batchId = batchId,
                uploadId = uploadId,
                validationAttemptId = attempt.id,
                contentSha256 = actualSha,
                decision = CONFIRM_EXACT_VERSION,
                confirmedAt = Instant.now(),
            ),
            identifierConfirmation,
        )
    }

    fun selectExactVersion(batchId: UUID, uploadId: UUID): RecoveryUploadAssetSelection {
        val context = validationContext(batchId, uploadId)
        val attempt = latestAttempt(batchId, uploadId, context)
        if (attempt.validationStatus != RecoveryValidationStatus.COMPLETED) {
            throw RecoveryStagingException("RECOVERY_VALIDATION_REQUIRED", 409, "Complete metadata validation before selecting this PDF version.")
        }
        if (attempt.identityOutcome == RecoveryIdentityOutcome.MISMATCH) {
            throw RecoveryStagingException("RECOVERY_UPLOAD_IDENTITY_MISMATCH", 409, "A metadata mismatch cannot be selected.")
        }
        if (attempt.identityOutcome == null) {
            throw RecoveryStagingException("RECOVERY_VALIDATION_REQUIRED", 409, "Complete metadata validation before selecting this PDF version.")
        }
        if (attempt.languageEligibility != RecoveryLanguageEligibility.ELIGIBLE) {
            throw RecoveryStagingException("RECOVERY_UPLOAD_NOT_ENGLISH_ELIGIBLE", 409, "This PDF is not eligible under the Analysis Run's English-language policy.")
        }
        val selectionMethod = when (attempt.identityOutcome) {
            RecoveryIdentityOutcome.VALIDATED -> RecoveryAssetSelectionMethod.MACHINE_VALIDATED
            RecoveryIdentityOutcome.NEEDS_CONFIRMATION -> {
                if (decisionRepository.confirmation(batchId, uploadId, attempt.id) == null) {
                    throw RecoveryStagingException("RECOVERY_IDENTITY_CONFIRMATION_REQUIRED", 409, "Confirm that this exact PDF is the cited work or intended version before selecting it.")
                }
                RecoveryAssetSelectionMethod.HUMAN_CONFIRMED
            }
            RecoveryIdentityOutcome.MISMATCH -> error("Mismatches are rejected before selection.")
        }
        val actualSha = context.upload.actualSha256
            ?: throw RecoveryStagingException("RECOVERY_UPLOAD_NOT_STAGED", 409, "The finalized PDF checksum is unavailable.")
        if (attempt.contentSha256 != actualSha) {
            throw RecoveryStagingException("RECOVERY_VALIDATION_STALE", 409, "The validated PDF bytes no longer match the staged snapshot.")
        }

        val selection = RecoveryUploadAssetSelection(
            id = UUID.randomUUID(),
            batchId = batchId,
            analysisRunId = context.upload.analysisRunId,
            bibliographyEntryId = context.upload.bibliographyEntryId,
            uploadId = uploadId,
            validationAttemptId = attempt.id,
            contentSha256 = actualSha,
            selectionMethod = selectionMethod,
            selectedAt = Instant.now(),
        )
        decisionRepository.recordSelection(selection)
        val existing = decisionRepository.selectionForEntry(batchId, context.upload.bibliographyEntryId)
            ?: throw IllegalStateException("The Recovery Upload selection was not persisted.")
        if (existing.uploadId != uploadId || existing.contentSha256 != actualSha) {
            throw RecoveryStagingException("RECOVERY_ENTRY_VERSION_ALREADY_SELECTED", 409, "Another exact PDF version is already selected for this Bibliography Entry. Remove it before selecting a replacement.")
        }
        return existing
    }

    fun confirmation(batchId: UUID, uploadId: UUID, attempt: RecoveryUploadValidationAttempt) =
        decisionRepository.confirmation(batchId, uploadId, attempt.id)

    fun selection(batchId: UUID, uploadId: UUID) = decisionRepository.selection(batchId, uploadId)

    private fun latestAttempt(
        batchId: UUID,
        uploadId: UUID,
        context: RecoveryUploadValidationContext,
    ): RecoveryUploadValidationAttempt {
        val attempt = validationRepository.latestAttempt(batchId, uploadId, Instant.now())
            ?: throw RecoveryStagingException("RECOVERY_VALIDATION_REQUIRED", 409, "Complete metadata validation before continuing.")
        if (attempt.identityPolicyVersion != RecoveryUploadIdentityPolicy.VERSION) {
            throw RecoveryStagingException("RECOVERY_IDENTITY_POLICY_STALE", 409, "Run validation again with the current identity policy before continuing.")
        }
        if (!attempt.matchesLanguagePolicy(context.languageEligibilityPolicy())) {
            throw RecoveryStagingException(
                "RECOVERY_LANGUAGE_POLICY_STALE",
                409,
                "Run validation again with the current language-eligibility policy before continuing.",
            )
        }
        if (!RecoveryUploadValidationCompatibility.matchesParserPolicy(attempt, context)) {
            throw RecoveryStagingException(
                "RECOVERY_VALIDATION_POLICY_STALE",
                409,
                "Run validation again with the current parser options and finalized PDF version before continuing.",
            )
        }
        return attempt
    }

    private fun validationContext(batchId: UUID, uploadId: UUID) =
        validationRepository.validationContext(batchId, uploadId, Instant.now())
            ?: throw RecoveryStagingException("RECOVERY_UPLOAD_NOT_FOUND", 404, "An active finalized Recovery Upload was not found.")

    companion object {
        const val CONFIRM_EXACT_VERSION = "CONFIRM_EXACT_VERSION"
        const val DOI_IDENTIFIER_TYPE = "DOI"
    }
}
