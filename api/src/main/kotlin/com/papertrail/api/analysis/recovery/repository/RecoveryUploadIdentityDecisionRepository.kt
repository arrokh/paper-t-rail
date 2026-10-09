package com.papertrail.api.analysis.recovery.repository

import com.papertrail.api.analysis.recovery.domain.RecoveryAssetSelectionMethod
import com.papertrail.api.analysis.recovery.domain.RecoveryIdentityConfirmation
import com.papertrail.api.analysis.recovery.domain.RecoveryStagingException
import com.papertrail.api.analysis.recovery.domain.RecoveryUploadAssetSelection
import com.papertrail.api.analysis.recovery.service.RecoveryStagingSettings
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

@Repository
class RecoveryUploadIdentityDecisionRepository(
    private val jdbc: JdbcTemplate,
    private val settings: RecoveryStagingSettings,
) {
    @Transactional
    fun recordConfirmation(confirmation: RecoveryIdentityConfirmation): RecoveryIdentityConfirmation {
        if (!lockActiveUpload(confirmation.batchId, confirmation.uploadId, confirmation.confirmedAt)) {
            throw RecoveryStagingException("RECOVERY_UPLOAD_NOT_FOUND", 404, "An active finalized Recovery Upload was not found.")
        }
        jdbc.update(
            """
            INSERT INTO recovery_upload_identity_confirmations (
                id, batch_id, upload_id, analysis_run_id, validation_attempt_id,
                content_sha256, decision, confirmation_version, confirmed_at
            ) VALUES (?, ?, ?, (SELECT analysis_run_id FROM recovery_upload_validation_attempts WHERE id = ?), ?, ?, ?, ?, ?)
            ON CONFLICT (validation_attempt_id) DO NOTHING
            """.trimIndent(),
            confirmation.id,
            confirmation.batchId,
            confirmation.uploadId,
            confirmation.validationAttemptId,
            confirmation.validationAttemptId,
            confirmation.contentSha256,
            confirmation.decision,
            CONFIRMATION_VERSION,
            java.sql.Timestamp.from(confirmation.confirmedAt),
        )
        touchBatch(confirmation.batchId, confirmation.confirmedAt)
        return confirmation(confirmation.batchId, confirmation.uploadId, confirmation.validationAttemptId)
            ?: throw IllegalStateException("The Recovery Upload confirmation was not persisted.")
    }

    fun confirmation(batchId: UUID, uploadId: UUID, attemptId: UUID): RecoveryIdentityConfirmation? = jdbc.query(
        """
        SELECT confirmation.*
          FROM recovery_upload_identity_confirmations confirmation
          JOIN recovery_batch_uploads upload ON upload.id = confirmation.upload_id
          JOIN recovery_batches batch ON batch.id = confirmation.batch_id
          JOIN analysis_runs run ON run.id = confirmation.analysis_run_id
          JOIN source_documents document ON document.id = run.document_id
         WHERE confirmation.batch_id = ?
           AND confirmation.upload_id = ?
           AND confirmation.validation_attempt_id = ?
           AND upload.status = 'STAGED'
           AND batch.status = 'OPEN'
           AND batch.expires_at > ?
           AND NOT EXISTS (
               SELECT 1 FROM source_document_tombstones tombstone
                WHERE tombstone.document_id = document.id
           )
        """.trimIndent(),
        { rs, _ ->
            RecoveryIdentityConfirmation(
                id = rs.getObject("id", UUID::class.java),
                batchId = rs.getObject("batch_id", UUID::class.java),
                uploadId = rs.getObject("upload_id", UUID::class.java),
                validationAttemptId = rs.getObject("validation_attempt_id", UUID::class.java),
                contentSha256 = rs.getString("content_sha256"),
                decision = rs.getString("decision"),
                confirmedAt = rs.getTimestamp("confirmed_at").toInstant(),
            )
        },
        batchId,
        uploadId,
        attemptId,
        java.sql.Timestamp.from(Instant.now()),
    ).firstOrNull()

    @Transactional
    fun recordSelection(selection: RecoveryUploadAssetSelection) {
        if (!lockActiveUpload(selection.batchId, selection.uploadId, selection.selectedAt)) {
            throw RecoveryStagingException("RECOVERY_UPLOAD_NOT_FOUND", 404, "An active finalized Recovery Upload was not found.")
        }
        jdbc.update(
            """
            INSERT INTO recovery_upload_asset_selections (
                id, batch_id, analysis_run_id, bibliography_entry_id, upload_id,
                validation_attempt_id, content_sha256, selection_method, selected_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (batch_id, bibliography_entry_id) DO NOTHING
            """.trimIndent(),
            selection.id,
            selection.batchId,
            selection.analysisRunId,
            selection.bibliographyEntryId,
            selection.uploadId,
            selection.validationAttemptId,
            selection.contentSha256,
            selection.selectionMethod.name,
            java.sql.Timestamp.from(selection.selectedAt),
        )
        touchBatch(selection.batchId, selection.selectedAt)
    }

    private fun lockActiveUpload(batchId: UUID, uploadId: UUID, now: Instant): Boolean = jdbc.query(
        """
        SELECT upload.id
          FROM recovery_batch_uploads upload
          JOIN recovery_batches batch ON batch.id = upload.batch_id
          JOIN analysis_runs run ON run.id = upload.analysis_run_id
          JOIN source_documents document ON document.id = run.document_id
         WHERE batch.id = ?
           AND upload.id = ?
           AND batch.status = 'OPEN'
           AND batch.expires_at > ?
           AND upload.status = 'STAGED'
           AND NOT EXISTS (
               SELECT 1 FROM source_document_tombstones tombstone
                WHERE tombstone.document_id = document.id
           )
         FOR UPDATE OF batch, upload
        """.trimIndent(),
        { rs, _ -> rs.getObject("id", UUID::class.java) },
        batchId,
        uploadId,
        java.sql.Timestamp.from(now),
    ).isNotEmpty()

    private fun touchBatch(batchId: UUID, now: Instant) {
        val updated = jdbc.update(
            """
            UPDATE recovery_batches
               SET last_activity_at = GREATEST(last_activity_at, ?),
                   expires_at = GREATEST(expires_at, ?)
             WHERE id = ? AND status = 'OPEN' AND expires_at > ?
            """.trimIndent(),
            java.sql.Timestamp.from(now),
            java.sql.Timestamp.from(now.plusSeconds(settings.inactivityTtlSeconds)),
            batchId,
            java.sql.Timestamp.from(now),
        )
        if (updated == 0) {
            throw RecoveryStagingException("RECOVERY_BATCH_EXPIRED", 410, "This Recovery Batch has expired.")
        }
    }

    fun selectionForEntry(batchId: UUID, bibliographyEntryId: UUID): RecoveryUploadAssetSelection? = jdbc.query(
        """
        SELECT *
          FROM recovery_upload_asset_selections
         WHERE batch_id = ? AND bibliography_entry_id = ?
        """.trimIndent(),
        { rs, _ -> rs.toSelection() },
        batchId,
        bibliographyEntryId,
    ).firstOrNull()

    fun selection(batchId: UUID, uploadId: UUID): RecoveryUploadAssetSelection? = jdbc.query(
        """
        SELECT selection.*
          FROM recovery_upload_asset_selections selection
          JOIN recovery_batch_uploads upload ON upload.id = selection.upload_id
          JOIN recovery_batches batch ON batch.id = selection.batch_id
          JOIN analysis_runs run ON run.id = selection.analysis_run_id
          JOIN source_documents document ON document.id = run.document_id
         WHERE selection.batch_id = ?
           AND selection.upload_id = ?
           AND selection.validation_attempt_id = (
               SELECT latest.id
                 FROM recovery_upload_validation_attempts latest
                WHERE latest.upload_id = selection.upload_id
                ORDER BY latest.created_at DESC, latest.id DESC
                LIMIT 1
           )
           AND upload.status = 'STAGED'
           AND batch.status = 'OPEN'
           AND batch.expires_at > ?
           AND NOT EXISTS (
               SELECT 1 FROM source_document_tombstones tombstone
                WHERE tombstone.document_id = document.id
           )
        """.trimIndent(),
        { rs, _ -> rs.toSelection() },
        batchId,
        uploadId,
        java.sql.Timestamp.from(Instant.now()),
    ).firstOrNull()

    private fun java.sql.ResultSet.toSelection() = RecoveryUploadAssetSelection(
        id = getObject("id", UUID::class.java),
        batchId = getObject("batch_id", UUID::class.java),
        analysisRunId = getObject("analysis_run_id", UUID::class.java),
        bibliographyEntryId = getObject("bibliography_entry_id", UUID::class.java),
        uploadId = getObject("upload_id", UUID::class.java),
        validationAttemptId = getObject("validation_attempt_id", UUID::class.java),
        contentSha256 = getString("content_sha256"),
        selectionMethod = RecoveryAssetSelectionMethod.valueOf(getString("selection_method")),
        selectedAt = getTimestamp("selected_at").toInstant(),
    )

    companion object {
        const val CONFIRMATION_VERSION = "recovery-identity-confirmation-v1"
    }
}
