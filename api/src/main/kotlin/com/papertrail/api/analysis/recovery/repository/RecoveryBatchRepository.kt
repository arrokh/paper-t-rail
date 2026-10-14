package com.papertrail.api.analysis.recovery.repository

import com.papertrail.api.analysis.recovery.domain.RecoveryBatch
import com.papertrail.api.analysis.recovery.domain.RecoveryBatchStatus
import com.papertrail.api.analysis.recovery.domain.RecoveryIdentifierAssistance
import com.papertrail.api.analysis.recovery.domain.RecoveryRightsDeclaration
import com.papertrail.api.analysis.recovery.domain.RecoveryStagingException
import com.papertrail.api.analysis.recovery.domain.RecoveryUpload
import com.papertrail.api.analysis.recovery.domain.RecoveryAssetSelectionMethod
import com.papertrail.api.analysis.recovery.domain.RecoveryUploadAssetSelection
import com.papertrail.api.analysis.recovery.domain.RecoveryUploadStatus
import com.papertrail.api.analysis.recovery.service.RecoveryStagingSettings
import com.papertrail.api.scholarly.references.resolver.ConservativeReferenceResolver
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID

@Repository
class RecoveryBatchRepository(
    private val jdbc: JdbcTemplate,
    private val settings: RecoveryStagingSettings,
    private val history: RecoveryHistoryRepository? = null,
) {
    fun analysisRunExists(analysisRunId: UUID): Boolean = jdbc.query(
        """
        SELECT run.id
          FROM analysis_runs run
          JOIN source_documents document ON document.id = run.document_id
          LEFT JOIN source_document_tombstones tombstone ON tombstone.document_id = document.id
         WHERE run.id = ?
           AND tombstone.document_id IS NULL
        """.trimIndent(),
        { rs, _ -> rs.getObject("id", UUID::class.java) },
        analysisRunId,
    ).isNotEmpty()

    fun activeBatch(analysisRunId: UUID, now: Instant): RecoveryBatch? {
        if (!analysisRunExists(analysisRunId)) {
            throw RecoveryStagingException("ANALYSIS_RUN_NOT_FOUND", 404, "Analysis Run not found.")
        }
        val batchId = jdbc.query(
            """
            SELECT batch.id
              FROM recovery_batches batch
             WHERE batch.analysis_run_id = ?
               AND batch.status = 'OPEN'
               AND batch.expires_at > ?
            """.trimIndent(),
            { rs, _ -> rs.getObject("id", UUID::class.java) },
            analysisRunId,
            java.sql.Timestamp.from(now),
        ).firstOrNull() ?: return null
        return loadBatch(batchId)
    }

    fun activeBatchById(batchId: UUID, now: Instant): RecoveryBatch {
        val state = jdbc.query(
            """
            SELECT batch.status, batch.expires_at,
                   EXISTS (
                       SELECT 1
                         FROM analysis_runs run
                         JOIN source_documents document ON document.id = run.document_id
                         LEFT JOIN source_document_tombstones tombstone ON tombstone.document_id = document.id
                        WHERE run.id = batch.analysis_run_id
                          AND tombstone.document_id IS NULL
                   ) AS source_document_active
              FROM recovery_batches batch
             WHERE batch.id = ?
            """.trimIndent(),
            { rs, _ ->
                RecoveryBatchActivity(
                    status = RecoveryBatchStatus.valueOf(rs.getString("status")),
                    expiresAt = rs.getTimestamp("expires_at").toInstant(),
                    sourceDocumentActive = rs.getBoolean("source_document_active"),
                )
            },
            batchId,
        ).firstOrNull() ?: throw RecoveryStagingException("RECOVERY_BATCH_NOT_FOUND", 404, "Recovery Batch not found.")

        if (!state.sourceDocumentActive) {
            throw RecoveryStagingException("ANALYSIS_RUN_NOT_FOUND", 404, "Analysis Run not found.")
        }
        if (state.status != RecoveryBatchStatus.OPEN || state.expiresAt <= now) {
            throw RecoveryStagingException("RECOVERY_BATCH_EXPIRED", 410, "This Recovery Batch has expired.")
        }
        return loadBatch(batchId)
            ?: throw RecoveryStagingException("RECOVERY_BATCH_NOT_FOUND", 404, "Recovery Batch not found.")
    }

    @Transactional
    fun createOrGetOpenBatch(
        analysisRunId: UUID,
        idempotencyKey: UUID,
        newBatchId: UUID,
        declaration: RecoveryRightsDeclaration?,
        now: Instant,
    ): RecoveryBatch? {
        if (!lockActiveRun(analysisRunId)) return null

        val idempotentBatch = jdbc.query(
            "SELECT id, creation_rights_declaration_version FROM recovery_batches WHERE analysis_run_id = ? AND idempotency_key = ?",
            { rs, _ -> rs.getObject("id", UUID::class.java) to rs.getString("creation_rights_declaration_version") },
            analysisRunId,
            idempotencyKey,
        ).firstOrNull()
        if (idempotentBatch != null) {
            if (idempotentBatch.second != declaration?.version) {
                throw RecoveryStagingException(
                    "RECOVERY_BATCH_IDEMPOTENCY_CONFLICT",
                    409,
                    "The idempotency key was already used with a different PDF-rights acceptance.",
                )
            }
            return loadBatch(idempotentBatch.first)
        }

        val openBatchId = jdbc.query(
            "SELECT id FROM recovery_batches WHERE analysis_run_id = ? AND status = 'OPEN' FOR UPDATE",
            { rs, _ -> rs.getObject("id", UUID::class.java) },
            analysisRunId,
        ).firstOrNull()
        if (openBatchId != null) {
            val existing = loadBatch(openBatchId) ?: return null
            if (existing.expiresAt > now) {
                if (declaration != null && existing.rightsDeclaration == null) {
                    return acceptCurrentRightsDeclaration(openBatchId, declaration, now)
                }
                if (declaration != null && existing.rightsDeclaration != declaration) {
                    throw RecoveryStagingException(
                        "RIGHTS_DECLARATION_ALREADY_RECORDED",
                        409,
                        "The Recovery Batch already has a different rights declaration snapshot.",
                    )
                }
                return existing
            }
            expireBatch(openBatchId, now)
        }

        jdbc.update(
            """
            INSERT INTO recovery_batches (
                id, analysis_run_id, idempotency_key, creation_rights_declaration_version,
                rights_declaration_version, rights_declaration_text, rights_declared_at,
                created_at, last_activity_at, expires_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            newBatchId,
            analysisRunId,
            idempotencyKey,
            declaration?.version,
            declaration?.version,
            declaration?.text,
            declaration?.let { java.sql.Timestamp.from(now) },
            java.sql.Timestamp.from(now),
            java.sql.Timestamp.from(now),
            java.sql.Timestamp.from(now.plusSeconds(settings.inactivityTtlSeconds)),
        )
        history?.append(
            batchId = newBatchId,
            analysisRunId = analysisRunId,
            eventType = "BATCH_CREATED",
            outcomeCode = if (declaration == null) "DOI_ONLY_READY" else "PDF_RIGHTS_ACCEPTED_AT_CREATION",
            createdAt = now,
        )
        if (declaration != null) {
            history?.append(
                batchId = newBatchId,
                analysisRunId = analysisRunId,
                eventType = "RIGHTS_ACCEPTED",
                outcomeCode = "PDF_RIGHTS_DECLARATION_ACCEPTED",
                createdAt = now,
            )
        }
        return loadBatch(newBatchId)
    }

    @Transactional
    fun acceptCurrentRightsDeclaration(batchId: UUID, declaration: RecoveryRightsDeclaration, now: Instant): RecoveryBatch {
        val batch = lockBatch(batchId) ?: throw RecoveryStagingException("RECOVERY_BATCH_NOT_FOUND", 404, "Recovery Batch not found.")
        if (batch.status != RecoveryBatchStatus.OPEN || batch.expiresAt <= now) {
            throw RecoveryStagingException("RECOVERY_BATCH_EXPIRED", 410, "This Recovery Batch has expired.")
        }
        if (batch.rightsDeclaration != null) {
            if (batch.rightsDeclaration != declaration || batch.rightsDeclaredAt == null) {
                throw RecoveryStagingException("RIGHTS_DECLARATION_ALREADY_RECORDED", 409, "The Recovery Batch already has a different rights declaration snapshot.")
            }
            return batch
        }
        jdbc.update(
            "UPDATE recovery_batches SET rights_declaration_version = ?, rights_declaration_text = ?, rights_declared_at = ? WHERE id = ? AND rights_declaration_version IS NULL",
            declaration.version,
            declaration.text,
            java.sql.Timestamp.from(now),
            batchId,
        )
        touchBatch(batchId, now)
        history?.append(
            batchId = batchId,
            analysisRunId = batch.analysisRunId,
            eventType = "RIGHTS_ACCEPTED",
            outcomeCode = "PDF_RIGHTS_DECLARATION_ACCEPTED",
            createdAt = now,
        )
        return loadBatch(batchId) ?: throw RecoveryStagingException("RECOVERY_BATCH_NOT_FOUND", 404, "Recovery Batch not found.")
    }

    @Transactional
    fun setIdentifierAssistance(
        batchId: UUID,
        localReferenceKey: String,
        normalizedDoi: String,
        now: Instant,
    ): RecoveryIdentifierAssistance {
        val batch = lockBatch(batchId) ?: throw RecoveryStagingException("RECOVERY_BATCH_NOT_FOUND", 404, "Recovery Batch not found.")
        if (batch.status != RecoveryBatchStatus.OPEN || batch.expiresAt <= now) {
            throw RecoveryStagingException("RECOVERY_BATCH_EXPIRED", 410, "This Recovery Batch has expired.")
        }
        val entry = jdbc.query(
            """
            SELECT entry.id, entry.reference_type, resolution.status AS resolution_status
              FROM bibliography_entries entry
              JOIN analysis_runs run ON run.id = entry.analysis_run_id
              JOIN source_documents document ON document.id = run.document_id
              LEFT JOIN source_document_tombstones tombstone ON tombstone.document_id = document.id
              LEFT JOIN bibliography_entry_resolutions resolution
                ON resolution.analysis_run_id = entry.analysis_run_id
               AND resolution.bibliography_entry_id = entry.id
             WHERE entry.analysis_run_id = ?
               AND entry.local_reference_key = ?
               AND tombstone.document_id IS NULL
            """.trimIndent(),
            { rs, _ -> IdentifierAssistanceEntry(
                bibliographyEntryId = rs.getObject("id", UUID::class.java),
                referenceType = rs.getString("reference_type"),
                resolutionStatus = rs.getString("resolution_status"),
            ) },
            batch.analysisRunId,
            localReferenceKey,
        ).firstOrNull() ?: throw RecoveryStagingException("BIBLIOGRAPHY_ENTRY_NOT_FOUND", 404, "Bibliography Entry not found in this Analysis Run.")
        if (entry.referenceType !in ConservativeReferenceResolver.SUPPORTED_REFERENCE_TYPES) {
            throw RecoveryStagingException("IDENTIFIER_ASSISTANCE_UNSUPPORTED_REFERENCE_TYPE", 409, "DOI assistance is not supported for this Bibliography Entry type.")
        }
        if (entry.resolutionStatus != null && entry.resolutionStatus != "UNRESOLVED") {
            throw RecoveryStagingException("IDENTIFIER_ASSISTANCE_ENTRY_ALREADY_RESOLVED", 409, "DOI assistance is available only for an unresolved Bibliography Entry.")
        }
        val current = jdbc.query(
            "SELECT id, identifier_value FROM recovery_batch_identifier_assistance WHERE batch_id = ? AND bibliography_entry_id = ? FOR UPDATE",
            { rs, _ -> rs.getObject("id", UUID::class.java) to rs.getString("identifier_value") },
            batchId,
            entry.bibliographyEntryId,
        ).firstOrNull()
        if (current?.second == normalizedDoi) return loadIdentifierAssistance(current.first)
            ?: throw IllegalStateException("Current DOI assistance could not be loaded.")
        val assistanceId = current?.first ?: UUID.randomUUID()
        if (current == null) {
            jdbc.update(
                "INSERT INTO recovery_batch_identifier_assistance (id, batch_id, analysis_run_id, bibliography_entry_id, identifier_type, identifier_value, created_at, updated_at) VALUES (?, ?, ?, ?, 'DOI', ?, ?, ?)",
                assistanceId, batchId, batch.analysisRunId, entry.bibliographyEntryId, normalizedDoi,
                java.sql.Timestamp.from(now), java.sql.Timestamp.from(now),
            )
        } else {
            jdbc.update(
                "UPDATE recovery_batch_identifier_assistance SET identifier_value = ?, updated_at = ? WHERE id = ?",
                normalizedDoi, java.sql.Timestamp.from(now), assistanceId,
            )
        }
        touchBatch(batchId, now)
        history?.append(
            batchId = batchId,
            analysisRunId = batch.analysisRunId,
            eventType = if (current == null) "IDENTIFIER_ASSISTANCE_SET" else "IDENTIFIER_ASSISTANCE_REPLACED",
            outcomeCode = if (current == null) "DOI_IDENTITY_CONFIRMED" else "DOI_IDENTITY_REVISED",
            stageCode = "reference-resolution",
            bibliographyEntryId = entry.bibliographyEntryId,
            createdAt = now,
        )
        return loadIdentifierAssistance(assistanceId)
            ?: throw IllegalStateException("Recorded DOI assistance could not be loaded.")
    }

    @Transactional
    fun removeIdentifierAssistance(batchId: UUID, localReferenceKey: String, now: Instant) {
        val batch = lockBatch(batchId) ?: throw RecoveryStagingException("RECOVERY_BATCH_NOT_FOUND", 404, "Recovery Batch not found.")
        if (batch.status != RecoveryBatchStatus.OPEN || batch.expiresAt <= now) {
            throw RecoveryStagingException("RECOVERY_BATCH_EXPIRED", 410, "This Recovery Batch has expired.")
        }
        val assistance = jdbc.query(
            """
            SELECT assistance.id, assistance.bibliography_entry_id
              FROM recovery_batch_identifier_assistance assistance
              JOIN bibliography_entries entry ON entry.id = assistance.bibliography_entry_id
             WHERE assistance.batch_id = ? AND entry.local_reference_key = ?
             FOR UPDATE OF assistance
            """.trimIndent(),
            { rs, _ -> rs.getObject("id", UUID::class.java) to rs.getObject("bibliography_entry_id", UUID::class.java) },
            batchId,
            localReferenceKey,
        ).firstOrNull() ?: return
        jdbc.update("DELETE FROM recovery_batch_identifier_assistance WHERE id = ?", assistance.first)
        touchBatch(batchId, now)
        history?.append(
            batchId = batchId,
            analysisRunId = batch.analysisRunId,
            eventType = "IDENTIFIER_ASSISTANCE_REMOVED",
            outcomeCode = "DOI_IDENTITY_REMOVED",
            stageCode = "reference-resolution",
            bibliographyEntryId = assistance.second,
            createdAt = now,
        )
    }

    @Transactional
    fun createUpload(
        batchId: UUID,
        localReferenceKey: String,
        idempotencyKey: UUID,
        newUploadId: UUID,
        filename: String,
        expectedSize: Long,
        expectedSha256: String,
        stagingObjectKey: String,
        finalizedObjectKey: String,
        now: Instant,
    ): RecoveryUpload {
        val batch = lockBatch(batchId) ?: throw RecoveryStagingException("RECOVERY_BATCH_NOT_FOUND", 404, "Recovery Batch not found.")
        if (batch.status != RecoveryBatchStatus.OPEN || batch.expiresAt <= now) {
            throw RecoveryStagingException("RECOVERY_BATCH_EXPIRED", 410, "This Recovery Batch has expired.")
        }
        if (batch.rightsDeclaration == null || batch.rightsDeclaredAt == null) {
            throw RecoveryStagingException("PDF_RIGHTS_DECLARATION_REQUIRED", 409, "Accept the current PDF-rights declaration before creating an upload intent.")
        }
        if (batch.rightsDeclaration != RecoveryRightsDeclaration.CURRENT) {
            throw RecoveryStagingException(
                "PDF_RIGHTS_DECLARATION_STALE",
                409,
                "The accepted PDF-rights declaration is no longer current, so this Recovery Batch cannot create a new upload intent.",
            )
        }

        val existingByKey = jdbc.query(
            "SELECT id FROM recovery_batch_uploads WHERE batch_id = ? AND idempotency_key = ?",
            { rs, _ -> rs.getObject("id", UUID::class.java) },
            batchId,
            idempotencyKey,
        ).firstOrNull()
        if (existingByKey != null) {
            val existing = loadUpload(existingByKey) ?: throw IllegalStateException("Recovery upload disappeared during an idempotent request.")
            if (existing.localReferenceKey != localReferenceKey || existing.filename != filename ||
                existing.expectedSize != expectedSize || existing.expectedSha256 != expectedSha256
            ) {
                throw RecoveryStagingException("IDEMPOTENCY_KEY_REUSED", 409, "This idempotency key was already used for a different upload.")
            }
            if (existing.status in setOf(RecoveryUploadStatus.REMOVED, RecoveryUploadStatus.EXPIRED, RecoveryUploadStatus.REJECTED)) {
                return existing
            }
            val nextExpiry = now.plusSeconds(settings.uploadUrlTtlSeconds)
            if (existing.status == RecoveryUploadStatus.PENDING_UPLOAD || existing.status == RecoveryUploadStatus.FINALIZING) {
                jdbc.update(
                    "UPDATE recovery_batch_uploads SET presigned_url_expires_at = GREATEST(presigned_url_expires_at, ?), updated_at = ? WHERE id = ?",
                    java.sql.Timestamp.from(nextExpiry), java.sql.Timestamp.from(now), existing.id,
                )
            }
            touchBatch(batchId, now)
            return loadUpload(existing.id) ?: existing
        }

        val entryExists = jdbc.query(
            """
            SELECT entry.id
              FROM bibliography_entries entry
              JOIN analysis_runs run ON run.id = entry.analysis_run_id
              JOIN source_documents document ON document.id = run.document_id
              LEFT JOIN source_document_tombstones tombstone ON tombstone.document_id = document.id
             WHERE entry.analysis_run_id = ?
               AND entry.local_reference_key = ?
               AND tombstone.document_id IS NULL
            """.trimIndent(),
            { rs, _ -> rs.getObject("id", UUID::class.java) },
            batch.analysisRunId,
            localReferenceKey,
        ).firstOrNull()
        val bibliographyEntryId = entryExists
            ?: throw RecoveryStagingException("BIBLIOGRAPHY_ENTRY_NOT_FOUND", 404, "Bibliography Entry not found in this Analysis Run.")

        val reserved = jdbc.queryForObject(
            """
            SELECT count(*) AS upload_count, COALESCE(sum(expected_size), 0) AS reserved_bytes
              FROM recovery_batch_uploads
             WHERE batch_id = ?
               AND (
                   status IN ('PENDING_UPLOAD', 'FINALIZING', 'STAGED')
                   OR (status IN ('REJECTED', 'REMOVED', 'EXPIRED') AND presigned_url_expires_at > ?)
               )
            """.trimIndent(),
            { rs, _ -> rs.getLong("upload_count") to rs.getLong("reserved_bytes") },
            batchId,
            java.sql.Timestamp.from(now.minusSeconds(settings.inFlightGraceSeconds)),
        ) ?: (0L to 0L)
        if (reserved.first >= settings.maxFilesPerBatch) {
            throw RecoveryStagingException("RECOVERY_BATCH_FILE_LIMIT", 413, "This Recovery Batch has reached its file limit.")
        }
        if (reserved.second + expectedSize > settings.maxBatchBytes) {
            throw RecoveryStagingException("RECOVERY_BATCH_SIZE_LIMIT", 413, "This Recovery Batch has reached its total size limit.")
        }

        val uploadUrlExpiresAt = now.plusSeconds(settings.uploadUrlTtlSeconds)
        jdbc.update(
            """
            INSERT INTO recovery_batch_uploads (
                id, batch_id, analysis_run_id, bibliography_entry_id, idempotency_key,
                filename, expected_size, expected_sha256, staging_object_key, finalized_object_key,
                presigned_url_expires_at, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            newUploadId,
            batchId,
            batch.analysisRunId,
            bibliographyEntryId,
            idempotencyKey,
            filename,
            expectedSize,
            expectedSha256,
            stagingObjectKey,
            finalizedObjectKey,
            java.sql.Timestamp.from(uploadUrlExpiresAt),
            java.sql.Timestamp.from(now),
            java.sql.Timestamp.from(now),
        )
        touchBatch(batchId, now)
        history?.append(
            batchId = batchId,
            analysisRunId = batch.analysisRunId,
            eventType = "UPLOAD_INTENT_CREATED",
            outcomeCode = "UPLOAD_INTENT_ACCEPTED",
            stageCode = "pdf-upload",
            bibliographyEntryId = bibliographyEntryId,
            uploadId = newUploadId,
            createdAt = now,
        )
        return loadUpload(newUploadId) ?: throw IllegalStateException("Created Recovery Upload could not be loaded.")
    }

    @Transactional
    fun beginFinalize(batchId: UUID, uploadId: UUID, now: Instant): RecoveryUpload {
        val batch = lockBatch(batchId) ?: throw RecoveryStagingException("RECOVERY_BATCH_NOT_FOUND", 404, "Recovery Batch not found.")
        if (batch.status != RecoveryBatchStatus.OPEN || batch.expiresAt <= now) {
            throw RecoveryStagingException("RECOVERY_BATCH_EXPIRED", 410, "This Recovery Batch has expired.")
        }
        val upload = loadUpload(uploadId, batchId)
            ?: throw RecoveryStagingException("RECOVERY_UPLOAD_NOT_FOUND", 404, "Recovery Upload not found.")
        when (upload.status) {
            RecoveryUploadStatus.STAGED -> return upload
            RecoveryUploadStatus.REJECTED -> throw RecoveryStagingException("RECOVERY_UPLOAD_REJECTED", 422, "This upload was rejected. Remove it before trying another PDF.")
            RecoveryUploadStatus.REMOVED, RecoveryUploadStatus.EXPIRED -> throw RecoveryStagingException("RECOVERY_UPLOAD_REMOVED", 410, "This Recovery Upload is no longer active.")
            RecoveryUploadStatus.PENDING_UPLOAD, RecoveryUploadStatus.FINALIZING -> Unit
        }
        jdbc.update(
            "UPDATE recovery_batch_uploads SET status = 'FINALIZING', updated_at = ? WHERE id = ? AND batch_id = ?",
            java.sql.Timestamp.from(now),
            uploadId,
            batchId,
        )
        touchBatch(batchId, now)
        return loadUpload(uploadId, batchId) ?: upload
    }

    @Transactional
    fun markStaged(uploadId: UUID, batchId: UUID, actualSize: Long, actualSha256: String, now: Instant): RecoveryUpload {
        val upload = loadUpload(uploadId, batchId)
            ?: throw RecoveryStagingException("RECOVERY_UPLOAD_NOT_FOUND", 404, "Recovery Upload not found.")
        if (upload.status !in setOf(RecoveryUploadStatus.FINALIZING, RecoveryUploadStatus.STAGED)) return upload
        val updated = jdbc.update(
            """
            UPDATE recovery_batch_uploads
               SET status = 'STAGED', failure_code = NULL, actual_size = ?, actual_sha256 = ?, finalized_at = COALESCE(finalized_at, ?), updated_at = ?
             WHERE id = ? AND batch_id = ? AND status IN ('FINALIZING', 'STAGED')
            """.trimIndent(),
            actualSize,
            actualSha256,
            java.sql.Timestamp.from(now),
            java.sql.Timestamp.from(now),
            uploadId,
            batchId,
        )
        if (updated > 0) {
            history?.append(
                batchId = batchId,
                analysisRunId = upload.analysisRunId,
                eventType = "UPLOAD_FINALIZED",
                outcomeCode = "PDF_SNAPSHOT_VERIFIED",
                stageCode = "pdf-upload",
                bibliographyEntryId = upload.bibliographyEntryId,
                uploadId = uploadId,
                createdAt = now,
            )
            val cleanupNotBefore = maxOf(now, upload.presignedUrlExpiresAt).plusSeconds(settings.inFlightGraceSeconds)
            insertCleanup(
                upload.stagingObjectKey,
                cleanupNotBefore,
                cleanupNotBefore.plusSeconds(settings.cleanupRetryWindowSeconds),
                now,
            )
            touchBatch(batchId, now)
        }
        return loadUpload(uploadId, batchId)
            ?: throw RecoveryStagingException("RECOVERY_UPLOAD_NOT_FOUND", 404, "Recovery Upload not found.")
    }

    @Transactional
    fun markRejected(uploadId: UUID, batchId: UUID, failureCode: String, now: Instant): RecoveryUpload? {
        val upload = jdbc.query(
            """
            SELECT upload.*, entry.local_reference_key
              FROM recovery_batch_uploads upload
              JOIN bibliography_entries entry
                ON entry.analysis_run_id = upload.analysis_run_id
               AND entry.id = upload.bibliography_entry_id
             WHERE upload.id = ? AND upload.batch_id = ?
             FOR UPDATE OF upload
            """.trimIndent(),
            { rs, _ -> mapUpload(rs) },
            uploadId,
            batchId,
        ).firstOrNull() ?: return null
        if (upload.status in setOf(RecoveryUploadStatus.STAGED, RecoveryUploadStatus.REJECTED, RecoveryUploadStatus.REMOVED, RecoveryUploadStatus.EXPIRED)) return upload
        insertCleanup(
            objectKey = upload.stagingObjectKey,
            notBefore = maxOf(now, upload.presignedUrlExpiresAt).plusSeconds(settings.inFlightGraceSeconds),
            retryUntil = maxOf(now, upload.presignedUrlExpiresAt).plusSeconds(settings.inFlightGraceSeconds + settings.cleanupRetryWindowSeconds),
            now = now,
        )
        insertCleanup(upload.finalizedObjectKey, now, now.plusSeconds(settings.cleanupRetryWindowSeconds), now)
        val updated = jdbc.update(
            "UPDATE recovery_batch_uploads SET status = 'REJECTED', failure_code = ?, updated_at = ? WHERE id = ? AND batch_id = ? AND status IN ('PENDING_UPLOAD', 'FINALIZING')",
            failureCode,
            java.sql.Timestamp.from(now),
            uploadId,
            batchId,
        )
        if (updated > 0) {
            history?.append(
                batchId = batchId,
                analysisRunId = upload.analysisRunId,
                eventType = "UPLOAD_REJECTED",
                outcomeCode = failureCode,
                stageCode = "pdf-upload",
                bibliographyEntryId = upload.bibliographyEntryId,
                uploadId = uploadId,
                createdAt = now,
            )
            touchBatch(batchId, now)
        }
        return loadUpload(uploadId, batchId)
    }

    @Transactional
    fun removeUpload(batchId: UUID, uploadId: UUID, now: Instant): RecoveryUpload {
        val batch = lockBatch(batchId) ?: throw RecoveryStagingException("RECOVERY_BATCH_NOT_FOUND", 404, "Recovery Batch not found.")
        val upload = loadUpload(uploadId, batchId)
            ?: throw RecoveryStagingException("RECOVERY_UPLOAD_NOT_FOUND", 404, "Recovery Upload not found.")
        if (upload.status == RecoveryUploadStatus.REMOVED) {
            jdbc.update("DELETE FROM recovery_upload_validation_attempts WHERE upload_id = ?", uploadId)
            return upload
        }
        if (batch.status != RecoveryBatchStatus.OPEN || batch.expiresAt <= now) {
            throw RecoveryStagingException("RECOVERY_BATCH_EXPIRED", 410, "This Recovery Batch has expired.")
        }
        val latestExpiry = maxOf(now, upload.presignedUrlExpiresAt)
        jdbc.update("DELETE FROM recovery_upload_validation_attempts WHERE upload_id = ?", uploadId)
        insertCleanup(
            upload.stagingObjectKey,
            latestExpiry.plusSeconds(settings.inFlightGraceSeconds),
            latestExpiry.plusSeconds(settings.inFlightGraceSeconds + settings.cleanupRetryWindowSeconds),
            now,
        )
        insertCleanup(upload.finalizedObjectKey, now, now.plusSeconds(settings.cleanupRetryWindowSeconds), now)
        jdbc.update(
            "UPDATE recovery_batch_uploads SET status = 'REMOVED', removed_at = ?, updated_at = ? WHERE id = ? AND batch_id = ?",
            java.sql.Timestamp.from(now),
            java.sql.Timestamp.from(now),
            uploadId,
            batchId,
        )
        history?.append(
            batchId = batchId,
            analysisRunId = upload.analysisRunId,
            eventType = "UPLOAD_REMOVED",
            outcomeCode = "UPLOAD_DETAILS_PURGED",
            stageCode = "pdf-upload",
            bibliographyEntryId = upload.bibliographyEntryId,
            uploadId = uploadId,
            createdAt = now,
        )
        history?.append(
            batchId = batchId,
            analysisRunId = upload.analysisRunId,
            eventType = "UPLOAD_DETAILS_PURGED",
            outcomeCode = "VALIDATION_DETAILS_PURGED",
            stageCode = "pdf-validation",
            bibliographyEntryId = upload.bibliographyEntryId,
            uploadId = uploadId,
            createdAt = now,
        )
        touchBatch(batchId, now)
        return loadUpload(uploadId, batchId) ?: upload
    }

    @Transactional
    fun expireDueBatches(now: Instant, limit: Int): Int {
        val dueBatchIds = jdbc.query(
            """
            SELECT id
              FROM recovery_batches
             WHERE status = 'OPEN' AND expires_at <= ?
             ORDER BY expires_at, id
             LIMIT ?
             FOR UPDATE SKIP LOCKED
            """.trimIndent(),
            { rs, _ -> rs.getObject("id", UUID::class.java) },
            java.sql.Timestamp.from(now),
            limit,
        )
        dueBatchIds.forEach { expireBatch(it, now) }
        return dueBatchIds.size
    }

    private fun lockActiveRun(analysisRunId: UUID): Boolean = jdbc.query(
        """
        SELECT run.id
          FROM analysis_runs run
          JOIN source_documents document ON document.id = run.document_id
          LEFT JOIN source_document_tombstones tombstone ON tombstone.document_id = document.id
         WHERE run.id = ? AND tombstone.document_id IS NULL
         FOR UPDATE OF run, document
        """.trimIndent(),
        { rs, _ -> rs.getObject("id", UUID::class.java) },
        analysisRunId,
    ).isNotEmpty()

    private fun lockBatch(batchId: UUID): RecoveryBatch? {
        val exists = jdbc.query(
            "SELECT id FROM recovery_batches WHERE id = ? FOR UPDATE",
            { rs, _ -> rs.getObject("id", UUID::class.java) },
            batchId,
        ).isNotEmpty()
        return if (exists) loadBatch(batchId) else null
    }

    private fun expireBatch(batchId: UUID, now: Instant) {
        val analysisRunId = jdbc.query(
            "SELECT analysis_run_id FROM recovery_batches WHERE id = ?",
            { rs, _ -> rs.getObject("analysis_run_id", UUID::class.java) },
            batchId,
        ).firstOrNull() ?: return
        val uploads = jdbc.query(
            """
            SELECT upload.*, entry.local_reference_key
              FROM recovery_batch_uploads upload
              JOIN bibliography_entries entry
                ON entry.analysis_run_id = upload.analysis_run_id
               AND entry.id = upload.bibliography_entry_id
             WHERE upload.batch_id = ?
             FOR UPDATE OF upload
            """.trimIndent(),
            { rs, _ -> mapUpload(rs) },
            batchId,
        )
        uploads.forEach { upload ->
            val notBefore = maxOf(now, upload.presignedUrlExpiresAt).plusSeconds(settings.inFlightGraceSeconds)
            insertCleanup(upload.stagingObjectKey, notBefore, notBefore.plusSeconds(settings.cleanupRetryWindowSeconds), now)
            insertCleanup(upload.finalizedObjectKey, now, now.plusSeconds(settings.cleanupRetryWindowSeconds), now)
        }
        val stagedIdentifierEntryIds = jdbc.query(
            "SELECT bibliography_entry_id FROM recovery_batch_identifier_assistance WHERE batch_id = ?",
            { rs, _ -> rs.getObject("bibliography_entry_id", UUID::class.java) },
            batchId,
        )
        jdbc.update("DELETE FROM recovery_batch_identifier_assistance WHERE batch_id = ?", batchId)
        jdbc.update("DELETE FROM recovery_upload_validation_attempts WHERE batch_id = ?", batchId)
        jdbc.update(
            "UPDATE recovery_batch_uploads SET status = 'EXPIRED', updated_at = ? WHERE batch_id = ? AND status <> 'REMOVED'",
            java.sql.Timestamp.from(now),
            batchId,
        )
        jdbc.update(
            "UPDATE recovery_batches SET status = 'EXPIRED', expired_at = ?, revision = revision + 1 WHERE id = ? AND status = 'OPEN'",
            java.sql.Timestamp.from(now),
            batchId,
        )
        uploads.filter { it.status != RecoveryUploadStatus.REMOVED }.forEach { upload ->
            history?.append(
                batchId = batchId, analysisRunId = analysisRunId, batchStatus = "EXPIRED",
                eventType = "UPLOAD_EXPIRED", outcomeCode = "BATCH_INACTIVITY_EXPIRY",
                stageCode = "pdf-upload", bibliographyEntryId = upload.bibliographyEntryId,
                uploadId = upload.id, createdAt = now,
            )
            history?.append(
                batchId = batchId, analysisRunId = analysisRunId, batchStatus = "EXPIRED",
                eventType = "UPLOAD_DETAILS_PURGED", outcomeCode = "VALIDATION_DETAILS_PURGED",
                stageCode = "pdf-validation", bibliographyEntryId = upload.bibliographyEntryId,
                uploadId = upload.id, createdAt = now,
            )
        }
        stagedIdentifierEntryIds.forEach { entryId ->
            history?.append(
                batchId = batchId, analysisRunId = analysisRunId, batchStatus = "EXPIRED",
                eventType = "IDENTIFIER_ASSISTANCE_REMOVED", outcomeCode = "DOI_ASSISTANCE_PURGED_ON_EXPIRY",
                stageCode = "reference-resolution", bibliographyEntryId = entryId, createdAt = now,
            )
        }
        history?.append(
            batchId = batchId, analysisRunId = analysisRunId, batchStatus = "EXPIRED",
            eventType = "UPLOAD_DETAILS_PURGED", outcomeCode = "BATCH_STAGING_DETAILS_PURGED",
            createdAt = now,
        )
    }

    private fun insertCleanup(objectKey: String, notBefore: Instant, retryUntil: Instant, now: Instant) {
        jdbc.update(
            """
            INSERT INTO recovery_upload_cleanup_tombstones (
                id, object_key, not_before, retry_until, next_attempt_at, created_at
            ) VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT (object_key) DO UPDATE
                SET not_before = GREATEST(recovery_upload_cleanup_tombstones.not_before, EXCLUDED.not_before),
                    retry_until = GREATEST(recovery_upload_cleanup_tombstones.retry_until, EXCLUDED.retry_until),
                    next_attempt_at = GREATEST(recovery_upload_cleanup_tombstones.next_attempt_at, EXCLUDED.next_attempt_at)
            """.trimIndent(),
            UUID.randomUUID(),
            objectKey,
            java.sql.Timestamp.from(notBefore),
            java.sql.Timestamp.from(retryUntil),
            java.sql.Timestamp.from(notBefore),
            java.sql.Timestamp.from(now),
        )
    }

    private fun touchBatch(batchId: UUID, now: Instant) {
        jdbc.update(
            "UPDATE recovery_batches SET last_activity_at = ?, expires_at = ?, revision = revision + 1 WHERE id = ? AND status = 'OPEN'",
            java.sql.Timestamp.from(now),
            java.sql.Timestamp.from(now.plusSeconds(settings.inactivityTtlSeconds)),
            batchId,
        )
    }

    private fun loadBatch(batchId: UUID): RecoveryBatch? {
        val batch = jdbc.query(
            "SELECT * FROM recovery_batches WHERE id = ?",
            { rs, _ -> mapBatch(rs) },
            batchId,
        ).firstOrNull() ?: return null
        val uploads = jdbc.query(
            """
            SELECT upload.*, entry.local_reference_key
              FROM recovery_batch_uploads upload
              JOIN bibliography_entries entry
                ON entry.analysis_run_id = upload.analysis_run_id
               AND entry.id = upload.bibliography_entry_id
             WHERE upload.batch_id = ?
             ORDER BY upload.created_at, upload.id
            """.trimIndent(),
            { rs, _ -> mapUpload(rs) },
            batchId,
        )
        val selectedAssets = jdbc.query(
            """
            SELECT selection.*
              FROM recovery_upload_asset_selections selection
              JOIN recovery_batch_uploads upload ON upload.id = selection.upload_id
             WHERE selection.batch_id = ?
               AND upload.status = 'STAGED'
               AND upload.actual_sha256 = selection.content_sha256
               AND selection.validation_attempt_id = (
                   SELECT latest.id
                     FROM recovery_upload_validation_attempts latest
                    WHERE latest.upload_id = selection.upload_id
                    ORDER BY latest.created_at DESC, latest.id DESC
                    LIMIT 1
               )
             ORDER BY selection.bibliography_entry_id
            """.trimIndent(),
            { rs, _ ->
                RecoveryUploadAssetSelection(
                    id = rs.getObject("id", UUID::class.java),
                    batchId = rs.getObject("batch_id", UUID::class.java),
                    analysisRunId = rs.getObject("analysis_run_id", UUID::class.java),
                    bibliographyEntryId = rs.getObject("bibliography_entry_id", UUID::class.java),
                    uploadId = rs.getObject("upload_id", UUID::class.java),
                    validationAttemptId = rs.getObject("validation_attempt_id", UUID::class.java),
                    contentSha256 = rs.getString("content_sha256"),
                    selectionMethod = RecoveryAssetSelectionMethod.valueOf(rs.getString("selection_method")),
                    selectedAt = rs.getTimestamp("selected_at").toInstant(),
                )
            },
            batchId,
        )
        val identifierAssistance = jdbc.query(
            """
            SELECT assistance.id, assistance.batch_id, assistance.analysis_run_id,
                   assistance.bibliography_entry_id, entry.local_reference_key,
                   assistance.identifier_type, assistance.identifier_value, assistance.updated_at
              FROM recovery_batch_identifier_assistance assistance
              JOIN bibliography_entries entry
                ON entry.analysis_run_id = assistance.analysis_run_id
               AND entry.id = assistance.bibliography_entry_id
             WHERE assistance.batch_id = ?
             ORDER BY entry.entry_order, entry.id
            """.trimIndent(),
            { rs, _ -> rs.toIdentifierAssistance() },
            batchId,
        )
        return batch.copy(uploads = uploads, selectedAssets = selectedAssets, identifierAssistance = identifierAssistance)
    }

    private fun loadIdentifierAssistance(assistanceId: UUID): RecoveryIdentifierAssistance? = jdbc.query(
        """
        SELECT assistance.id, assistance.batch_id, assistance.analysis_run_id,
               assistance.bibliography_entry_id, entry.local_reference_key,
               assistance.identifier_type, assistance.identifier_value, assistance.updated_at
          FROM recovery_batch_identifier_assistance assistance
          JOIN bibliography_entries entry
            ON entry.analysis_run_id = assistance.analysis_run_id
           AND entry.id = assistance.bibliography_entry_id
         WHERE assistance.id = ?
        """.trimIndent(),
        { rs, _ -> rs.toIdentifierAssistance() },
        assistanceId,
    ).firstOrNull()

    private fun ResultSet.toIdentifierAssistance() = RecoveryIdentifierAssistance(
        id = getObject("id", UUID::class.java),
        batchId = getObject("batch_id", UUID::class.java),
        analysisRunId = getObject("analysis_run_id", UUID::class.java),
        bibliographyEntryId = getObject("bibliography_entry_id", UUID::class.java),
        localReferenceKey = getString("local_reference_key"),
        identifierType = getString("identifier_type"),
        identifierValue = getString("identifier_value"),
        recordedAt = getTimestamp("updated_at").toInstant(),
    )

    private fun loadUpload(uploadId: UUID, batchId: UUID? = null): RecoveryUpload? {
        val sql = if (batchId == null) {
            """
            SELECT upload.*, entry.local_reference_key
              FROM recovery_batch_uploads upload
              JOIN bibliography_entries entry
                ON entry.analysis_run_id = upload.analysis_run_id
               AND entry.id = upload.bibliography_entry_id
             WHERE upload.id = ?
            """.trimIndent()
        } else {
            """
            SELECT upload.*, entry.local_reference_key
              FROM recovery_batch_uploads upload
              JOIN bibliography_entries entry
                ON entry.analysis_run_id = upload.analysis_run_id
               AND entry.id = upload.bibliography_entry_id
             WHERE upload.id = ? AND upload.batch_id = ?
            """.trimIndent()
        }
        return if (batchId == null) {
            jdbc.query(sql, { rs, _ -> mapUpload(rs) }, uploadId).firstOrNull()
        } else {
            jdbc.query(sql, { rs, _ -> mapUpload(rs) }, uploadId, batchId).firstOrNull()
        }
    }

    private fun mapBatch(rs: ResultSet) = RecoveryBatch(
        id = rs.getObject("id", UUID::class.java),
        analysisRunId = rs.getObject("analysis_run_id", UUID::class.java),
        status = RecoveryBatchStatus.valueOf(rs.getString("status")),
        rightsDeclaration = rs.getString("rights_declaration_version")?.let { version ->
            RecoveryRightsDeclaration(version, rs.getString("rights_declaration_text"))
        },
        rightsDeclaredAt = rs.getTimestamp("rights_declared_at")?.toInstant(),
        createdAt = rs.getTimestamp("created_at").toInstant(),
        lastActivityAt = rs.getTimestamp("last_activity_at").toInstant(),
        expiresAt = rs.getTimestamp("expires_at").toInstant(),
        revision = rs.getLong("revision"),
        uploads = emptyList(),
        selectedAssets = emptyList(),
        identifierAssistance = emptyList(),
    )

    private fun mapUpload(rs: ResultSet) = RecoveryUpload(
        id = rs.getObject("id", UUID::class.java),
        batchId = rs.getObject("batch_id", UUID::class.java),
        analysisRunId = rs.getObject("analysis_run_id", UUID::class.java),
        bibliographyEntryId = rs.getObject("bibliography_entry_id", UUID::class.java),
        localReferenceKey = rs.getString("local_reference_key"),
        idempotencyKey = rs.getObject("idempotency_key", UUID::class.java),
        filename = rs.getString("filename"),
        expectedSize = rs.getLong("expected_size"),
        expectedSha256 = rs.getString("expected_sha256"),
        stagingObjectKey = rs.getString("staging_object_key"),
        finalizedObjectKey = rs.getString("finalized_object_key"),
        status = RecoveryUploadStatus.valueOf(rs.getString("status")),
        failureCode = rs.getString("failure_code"),
        presignedUrlExpiresAt = rs.getTimestamp("presigned_url_expires_at").toInstant(),
        actualSize = rs.getLong("actual_size").let { if (rs.wasNull()) null else it },
        actualSha256 = rs.getString("actual_sha256"),
        createdAt = rs.getTimestamp("created_at").toInstant(),
        updatedAt = rs.getTimestamp("updated_at").toInstant(),
        finalizedAt = rs.getTimestamp("finalized_at")?.toInstant(),
    )
}

private data class RecoveryBatchActivity(
    val status: RecoveryBatchStatus,
    val expiresAt: Instant,
    val sourceDocumentActive: Boolean,
)

private data class IdentifierAssistanceEntry(
    val bibliographyEntryId: UUID,
    val referenceType: String,
    val resolutionStatus: String?,
)
