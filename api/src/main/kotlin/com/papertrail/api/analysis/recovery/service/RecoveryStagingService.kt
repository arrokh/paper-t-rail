package com.papertrail.api.analysis.recovery.service

import com.papertrail.api.analysis.recovery.domain.RecoveryBatch
import com.papertrail.api.analysis.recovery.domain.RecoveryBatchStatus
import com.papertrail.api.analysis.recovery.domain.RecoveryPdfValidationException
import com.papertrail.api.analysis.recovery.domain.RecoveryRightsDeclaration
import com.papertrail.api.analysis.recovery.domain.RecoveryStagingException
import com.papertrail.api.analysis.recovery.domain.RecoveryUpload
import com.papertrail.api.analysis.recovery.domain.RecoveryUploadIntent
import com.papertrail.api.analysis.recovery.domain.RecoveryUploadStatus
import com.papertrail.api.analysis.recovery.repository.RecoveryBatchRepository
import com.papertrail.api.infrastructure.crypto.sha256Hex
import com.papertrail.api.infrastructure.storage.PresignedObjectUpload
import com.papertrail.api.infrastructure.storage.SourceDocumentObjectStore
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import software.amazon.awssdk.services.s3.model.S3Exception
import java.time.Instant
import java.util.UUID

@Service
class RecoveryStagingService(
    private val repository: RecoveryBatchRepository,
    private val objectStore: SourceDocumentObjectStore,
    private val settings: RecoveryStagingSettings,
    private val corsPolicy: RecoveryUploadCorsPolicy,
    private val pdfValidator: RecoveryPdfValidator,
) {
    fun activeBatch(analysisRunId: UUID): RecoveryBatch? = repository.activeBatch(analysisRunId, Instant.now())

    fun currentRightsDeclaration(): RecoveryRightsDeclaration = RecoveryRightsDeclaration.CURRENT

    fun currentLimits() = RecoveryUploadLimits(
        maxFileBytes = settings.maxFileBytes,
        maxFilesPerBatch = settings.maxFilesPerBatch,
        maxBatchBytes = settings.maxBatchBytes,
        uploadUrlTtlSeconds = settings.uploadUrlTtlSeconds,
        inactivityTtlSeconds = settings.inactivityTtlSeconds,
    )

    fun createBatch(
        analysisRunId: UUID,
        idempotencyKey: UUID,
        rightsDeclarationVersion: String,
        rightsDeclarationAccepted: Boolean,
    ): RecoveryBatch {
        if (rightsDeclarationVersion != RecoveryRightsDeclaration.CURRENT.version) {
            throw RecoveryStagingException("RIGHTS_DECLARATION_CHANGED", 409, "The rights declaration changed. Review the current declaration before continuing.")
        }
        if (!rightsDeclarationAccepted) {
            throw RecoveryStagingException("RIGHTS_DECLARATION_REQUIRED", 400, "Accept the rights declaration before creating a Recovery Batch.")
        }
        val batch = repository.createOrGetOpenBatch(
            analysisRunId = analysisRunId,
            idempotencyKey = idempotencyKey,
            newBatchId = UUID.randomUUID(),
            declaration = RecoveryRightsDeclaration.CURRENT,
            now = Instant.now(),
        ) ?: throw RecoveryStagingException("ANALYSIS_RUN_NOT_FOUND", 404, "Analysis Run not found.")
        if (batch.status != RecoveryBatchStatus.OPEN || batch.expiresAt <= Instant.now()) {
            throw RecoveryStagingException("RECOVERY_BATCH_EXPIRED", 410, "This Recovery Batch has expired.")
        }
        return batch
    }

    fun createUploadIntent(
        batchId: UUID,
        localReferenceKey: String,
        idempotencyKey: UUID,
        filename: String,
        expectedSize: Long,
        expectedSha256: String,
    ): RecoveryUploadIntent {
        if (expectedSize <= 0) {
            throw RecoveryStagingException("INVALID_UPLOAD_SIZE", 400, "Choose a non-empty PDF file.")
        }
        if (expectedSize > settings.maxFileBytes) {
            throw RecoveryStagingException("UPLOAD_TOO_LARGE", 413, "The PDF exceeds the configured Analysis Run upload limit.")
        }
        if (!expectedSha256.matches(LOWERCASE_SHA256)) {
            throw RecoveryStagingException("INVALID_UPLOAD_CHECKSUM", 400, "A lowercase SHA-256 checksum is required.")
        }
        val safeFilename = sanitizeFilename(filename)
        if (!safeFilename.endsWith(".pdf", ignoreCase = true)) {
            throw RecoveryStagingException("PDF_FILENAME_REQUIRED", 400, "The selected file must have a .pdf filename.")
        }

        val uploadId = UUID.randomUUID()
        val now = Instant.now()
        val upload = repository.createUpload(
            batchId = batchId,
            localReferenceKey = localReferenceKey,
            idempotencyKey = idempotencyKey,
            newUploadId = uploadId,
            filename = safeFilename,
            expectedSize = expectedSize,
            expectedSha256 = expectedSha256,
            stagingObjectKey = "recovery/staging/$batchId/$uploadId.pdf",
            finalizedObjectKey = "recovery/finalized/$batchId/$uploadId/$expectedSha256.pdf",
            now = now,
        )

        if (upload.status == RecoveryUploadStatus.STAGED) return RecoveryUploadIntent(upload, null, null)
        if (upload.status == RecoveryUploadStatus.REJECTED) {
            throw RecoveryStagingException("RECOVERY_UPLOAD_REJECTED", 422, "This upload was rejected. Remove it before trying another PDF.")
        }
        if (upload.status == RecoveryUploadStatus.REMOVED || upload.status == RecoveryUploadStatus.EXPIRED) {
            throw RecoveryStagingException("RECOVERY_UPLOAD_REMOVED", 410, "This Recovery Upload is no longer active.")
        }

        corsPolicy.ensureConfigured()
        val signed = try {
            objectStore.presignPutPdf(upload.stagingObjectKey, upload.expectedSize, upload.expectedSha256, settings.uploadUrlTtlSeconds.toInt())
        } catch (_: Exception) {
            throw RecoveryStagingException("UPLOAD_STORAGE_UNAVAILABLE", 503, "Browser upload storage is not available.")
        }
        return RecoveryUploadIntent(upload, signed, upload.presignedUrlExpiresAt)
    }

    fun finalizeUpload(batchId: UUID, uploadId: UUID): RecoveryUpload {
        val upload = repository.beginFinalize(batchId, uploadId, Instant.now())
        if (upload.status == RecoveryUploadStatus.STAGED) return upload

        val bytes = try {
            objectStore.get(upload.stagingObjectKey)
        } catch (exception: S3Exception) {
            if (exception.statusCode() == 404) {
                throw RecoveryStagingException("UPLOAD_BYTES_MISSING", 409, "The uploaded PDF was not found. Upload it again before finalizing.")
            }
            throw RecoveryStagingException("UPLOAD_STORAGE_UNAVAILABLE", 503, "Browser upload storage is not available.")
        } catch (_: Exception) {
            throw RecoveryStagingException("UPLOAD_STORAGE_UNAVAILABLE", 503, "Browser upload storage is not available.")
        }

        if (bytes.size.toLong() != upload.expectedSize) {
            rejectUpload(upload, "UPLOAD_SIZE_MISMATCH", "The uploaded PDF size did not match the declared size.")
        }
        val actualSha256 = sha256Hex(bytes)
        if (actualSha256 != upload.expectedSha256) {
            rejectUpload(upload, "UPLOAD_CHECKSUM_MISMATCH", "The uploaded PDF checksum did not match the declared checksum.")
        }
        try {
            pdfValidator.validate(bytes)
        } catch (exception: RecoveryPdfValidationException) {
            rejectUpload(upload, exception.code, exception.message ?: "The uploaded PDF could not be validated.")
        }

        try {
            objectStore.put(upload.finalizedObjectKey, bytes, PDF_CONTENT_TYPE)
            val finalizedMetadata = objectStore.stat(upload.finalizedObjectKey)
            if (finalizedMetadata.size != upload.expectedSize || finalizedMetadata.sha256 != actualSha256) {
                throw RecoveryStagingException("FINALIZED_SNAPSHOT_MISMATCH", 503, "The finalized PDF snapshot could not be verified.")
            }
        } catch (exception: RecoveryStagingException) {
            throw exception
        } catch (_: Exception) {
            throw RecoveryStagingException("UPLOAD_STORAGE_UNAVAILABLE", 503, "The finalized PDF could not be stored.")
        }

        val staged = repository.markStaged(upload.id, upload.batchId, upload.expectedSize, actualSha256, Instant.now())
        if (staged.status != RecoveryUploadStatus.STAGED) {
            deleteImmediately(upload.finalizedObjectKey, upload.id)
            throw RecoveryStagingException("RECOVERY_UPLOAD_REMOVED", 410, "This Recovery Upload is no longer active.")
        }
        deleteImmediately(upload.stagingObjectKey, upload.id)
        return staged
    }

    fun removeUpload(batchId: UUID, uploadId: UUID): RecoveryUpload {
        val removed = repository.removeUpload(batchId, uploadId, Instant.now())
        deleteImmediately(removed.stagingObjectKey, removed.id)
        deleteImmediately(removed.finalizedObjectKey, removed.id)
        return removed
    }

    private fun rejectUpload(upload: RecoveryUpload, code: String, message: String): Nothing {
        val rejected = repository.markRejected(upload.id, upload.batchId, code, Instant.now())
        deleteImmediately(upload.stagingObjectKey, upload.id)
        if (rejected?.status == RecoveryUploadStatus.STAGED) {
            throw RecoveryStagingException("RECOVERY_UPLOAD_ALREADY_FINALIZED", 409, "This Recovery Upload has already been finalized.")
        }
        deleteImmediately(upload.finalizedObjectKey, upload.id)
        if (rejected?.status != RecoveryUploadStatus.REJECTED) {
            throw RecoveryStagingException("RECOVERY_UPLOAD_REMOVED", 410, "This Recovery Upload is no longer active.")
        }
        throw RecoveryStagingException(code, 422, message)
    }

    private fun deleteImmediately(objectKey: String, uploadId: UUID) {
        try {
            objectStore.delete(objectKey)
        } catch (exception: Exception) {
            logger.atWarn()
                .addKeyValue("recoveryUploadId", uploadId)
                .addKeyValue("errorType", exception.javaClass.simpleName)
                .log("Recovery upload object deletion failed; durable cleanup is scheduled")
        }
    }

    private fun sanitizeFilename(filename: String): String {
        val leaf = filename.replace('\\', '/').substringAfterLast('/').trim()
        val safe = leaf.map { character ->
            if (character.isLetterOrDigit() || character in " ._-()") character else '_'
        }.joinToString("").trim().trim('.')
        return safe.take(MAX_FILENAME_LENGTH).ifBlank { "upload.pdf" }
    }

    companion object {
        private const val PDF_CONTENT_TYPE = "application/pdf"
        private const val MAX_FILENAME_LENGTH = 120
        private val LOWERCASE_SHA256 = Regex("^[0-9a-f]{64}$")
        private val logger = LoggerFactory.getLogger(RecoveryStagingService::class.java)
    }
}
