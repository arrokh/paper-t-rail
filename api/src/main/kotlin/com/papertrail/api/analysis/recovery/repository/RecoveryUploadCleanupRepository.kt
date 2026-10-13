package com.papertrail.api.analysis.recovery.repository

import com.papertrail.api.analysis.recovery.domain.RecoveryCleanupObject
import com.papertrail.api.analysis.recovery.service.RecoveryStagingSettings
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

@Repository
class RecoveryUploadCleanupRepository(
    private val jdbc: JdbcTemplate,
    private val settings: RecoveryStagingSettings,
) {
    @Transactional
    fun scheduleSourceDocumentUploads(documentId: UUID, now: Instant) {
        jdbc.update(
            """
            INSERT INTO recovery_upload_cleanup_tombstones (
                id, object_key, not_before, retry_until, next_attempt_at, created_at
            )
            SELECT gen_random_uuid(), upload.staging_object_key,
                   GREATEST(upload.presigned_url_expires_at, ?) + (? * INTERVAL '1 second'),
                   GREATEST(upload.presigned_url_expires_at, ?) + ((? + ?) * INTERVAL '1 second'),
                   GREATEST(upload.presigned_url_expires_at, ?) + (? * INTERVAL '1 second'),
                   ?
              FROM recovery_batch_uploads upload
              JOIN analysis_runs run ON run.id = upload.analysis_run_id
             WHERE run.document_id = ?
            ON CONFLICT (object_key) DO UPDATE
                SET not_before = GREATEST(recovery_upload_cleanup_tombstones.not_before, EXCLUDED.not_before),
                    retry_until = GREATEST(recovery_upload_cleanup_tombstones.retry_until, EXCLUDED.retry_until),
                    next_attempt_at = GREATEST(recovery_upload_cleanup_tombstones.next_attempt_at, EXCLUDED.next_attempt_at)
            """.trimIndent(),
            java.sql.Timestamp.from(now),
            settings.inFlightGraceSeconds,
            java.sql.Timestamp.from(now),
            settings.inFlightGraceSeconds,
            settings.cleanupRetryWindowSeconds,
            java.sql.Timestamp.from(now),
            settings.inFlightGraceSeconds,
            java.sql.Timestamp.from(now),
            documentId,
        )
        jdbc.update(
            """
            INSERT INTO recovery_upload_cleanup_tombstones (
                id, object_key, not_before, retry_until, next_attempt_at, created_at
            )
            SELECT gen_random_uuid(), upload.finalized_object_key, CAST(? AS TIMESTAMPTZ),
                   CAST(? AS TIMESTAMPTZ) + (CAST(? AS DOUBLE PRECISION) * INTERVAL '1 second'),
                   CAST(? AS TIMESTAMPTZ), CAST(? AS TIMESTAMPTZ)
              FROM recovery_batch_uploads upload
              JOIN analysis_runs run ON run.id = upload.analysis_run_id
             WHERE run.document_id = ?
            ON CONFLICT (object_key) DO UPDATE
                SET not_before = GREATEST(recovery_upload_cleanup_tombstones.not_before, EXCLUDED.not_before),
                    retry_until = GREATEST(recovery_upload_cleanup_tombstones.retry_until, EXCLUDED.retry_until),
                    next_attempt_at = GREATEST(recovery_upload_cleanup_tombstones.next_attempt_at, EXCLUDED.next_attempt_at)
            """.trimIndent(),
            java.sql.Timestamp.from(now),
            java.sql.Timestamp.from(now),
            settings.cleanupRetryWindowSeconds,
            java.sql.Timestamp.from(now),
            java.sql.Timestamp.from(now),
            documentId,
        )
    }

    @Transactional
    fun scheduleSubmittedUnownedUpload(analysisRunId: UUID, bibliographyEntryId: UUID, now: Instant): Boolean {
        val upload = jdbc.query(
            """
            SELECT upload.id, upload.staging_object_key, upload.finalized_object_key, upload.presigned_url_expires_at
              FROM analysis_run_successor_work_manifest manifest
              JOIN recovery_batches batch ON batch.id = manifest.batch_id
              JOIN recovery_batch_uploads upload
                ON upload.batch_id = manifest.batch_id AND upload.id = manifest.selected_upload_id
             WHERE manifest.successor_analysis_run_id = ?
               AND manifest.bibliography_entry_id = ?
               AND manifest.disposition = 'REPROCESS'
               AND batch.status = 'SUBMITTED'
               AND upload.status = 'STAGED'
             FOR UPDATE OF manifest, batch, upload
            """.trimIndent(),
            { rs, _ ->
                SubmittedUploadForCleanup(
                    id = rs.getObject("id", UUID::class.java),
                    stagingObjectKey = rs.getString("staging_object_key"),
                    finalizedObjectKey = rs.getString("finalized_object_key"),
                    presignedUrlExpiresAt = rs.getTimestamp("presigned_url_expires_at").toInstant(),
                )
            },
            analysisRunId,
            bibliographyEntryId,
        ).firstOrNull() ?: return false
        val alreadyOwned = jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM analysis_run_successor_asset_ownership WHERE upload_id = ? OR object_key = ?) OR EXISTS (SELECT 1 FROM cited_paper_access WHERE object_key = ?)",
            Boolean::class.java,
            upload.id,
            upload.finalizedObjectKey,
            upload.finalizedObjectKey,
        ) == true
        if (alreadyOwned) return false

        val stagingNotBefore = maxOf(upload.presignedUrlExpiresAt, now).plusSeconds(settings.inFlightGraceSeconds)
        insertCleanup(
            upload.stagingObjectKey,
            stagingNotBefore,
            stagingNotBefore.plusSeconds(settings.cleanupRetryWindowSeconds),
            now,
        )
        insertCleanup(
            upload.finalizedObjectKey,
            now,
            now.plusSeconds(settings.cleanupRetryWindowSeconds),
            now,
        )
        return true
    }

    @Transactional
    fun claimDue(now: Instant, retryIntervalSeconds: Long, limit: Int): List<RecoveryCleanupObject> = jdbc.query(
        """
        WITH due AS (
            SELECT id
              FROM recovery_upload_cleanup_tombstones
             WHERE next_attempt_at <= ?
             ORDER BY next_attempt_at, id
             LIMIT ?
             FOR UPDATE SKIP LOCKED
        )
        UPDATE recovery_upload_cleanup_tombstones cleanup
           SET next_attempt_at = LEAST(CAST(? AS TIMESTAMPTZ) + (CAST(? AS DOUBLE PRECISION) * INTERVAL '1 second'), cleanup.retry_until),
               attempts = cleanup.attempts + 1
          FROM due
         WHERE cleanup.id = due.id
        RETURNING cleanup.id, cleanup.object_key, cleanup.retry_until
        """.trimIndent(),
        { rs, _ ->
            RecoveryCleanupObject(
                id = rs.getObject("id", UUID::class.java),
                objectKey = rs.getString("object_key"),
                retryUntil = rs.getTimestamp("retry_until").toInstant(),
            )
        },
        java.sql.Timestamp.from(now),
        limit,
        java.sql.Timestamp.from(now),
        retryIntervalSeconds,
    )

    fun markDeleted(id: UUID, now: Instant, retryIntervalSeconds: Long, retryUntil: Instant) {
        if (now >= retryUntil) {
            jdbc.update("DELETE FROM recovery_upload_cleanup_tombstones WHERE id = ?", id)
            return
        }
        jdbc.update(
            """
            UPDATE recovery_upload_cleanup_tombstones
               SET last_success_at = ?, last_error_type = NULL,
                   next_attempt_at = LEAST(CAST(? AS TIMESTAMPTZ) + (CAST(? AS DOUBLE PRECISION) * INTERVAL '1 second'), retry_until)
             WHERE id = ?
            """.trimIndent(),
            java.sql.Timestamp.from(now),
            java.sql.Timestamp.from(now),
            retryIntervalSeconds,
            id,
        )
    }

    fun markFailure(id: UUID, now: Instant, retryIntervalSeconds: Long, errorType: String, retryUntil: Instant) {
        if (now >= retryUntil) {
            jdbc.update("DELETE FROM recovery_upload_cleanup_tombstones WHERE id = ?", id)
            return
        }
        jdbc.update(
            """
            UPDATE recovery_upload_cleanup_tombstones
               SET last_error_type = ?, next_attempt_at = LEAST(CAST(? AS TIMESTAMPTZ) + (CAST(? AS DOUBLE PRECISION) * INTERVAL '1 second'), retry_until)
             WHERE id = ?
            """.trimIndent(),
            errorType.take(128),
            java.sql.Timestamp.from(now),
            retryIntervalSeconds,
            id,
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
}

private data class SubmittedUploadForCleanup(
    val id: UUID,
    val stagingObjectKey: String,
    val finalizedObjectKey: String,
    val presignedUrlExpiresAt: Instant,
)
