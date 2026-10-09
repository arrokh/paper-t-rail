package com.papertrail.api.analysis.recovery.repository

import com.fasterxml.jackson.core.type.TypeReference
import com.papertrail.api.analysis.configuration.AnalysisConfigurationSnapshot
import com.papertrail.api.analysis.recovery.domain.RecoveryIdentityOutcome
import com.papertrail.api.analysis.recovery.domain.RecoveryLanguageEligibility
import com.papertrail.api.analysis.recovery.domain.RecoveryUpload
import com.papertrail.api.analysis.recovery.domain.RecoveryUploadStatus
import com.papertrail.api.analysis.recovery.domain.RecoveryStagingException
import com.papertrail.api.analysis.recovery.domain.RecoveryUploadValidationAttempt
import com.papertrail.api.analysis.recovery.domain.RecoveryUploadValidationContext
import com.papertrail.api.analysis.recovery.domain.RecoveryValidationStatus
import com.papertrail.api.analysis.recovery.service.RecoveryStagingSettings
import com.papertrail.api.citation.parsing.ParsedBibliographicMetadataCandidate
import com.papertrail.api.utils.JsonUtil
import com.papertrail.api.scholarly.references.client.BibliographyReference
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

@Repository
class RecoveryUploadValidationRepository(
    private val jdbc: JdbcTemplate,
    private val settings: RecoveryStagingSettings,
) {
    fun validationContext(batchId: UUID, uploadId: UUID, now: Instant): RecoveryUploadValidationContext? =
        jdbc.query(
            """
            SELECT upload.id,
                   upload.batch_id,
                   upload.analysis_run_id,
                   upload.bibliography_entry_id,
                   entry.local_reference_key,
                   upload.idempotency_key,
                   upload.filename,
                   upload.expected_size,
                   upload.expected_sha256,
                   upload.staging_object_key,
                   upload.finalized_object_key,
                   upload.status,
                   upload.failure_code,
                   upload.presigned_url_expires_at,
                   upload.actual_size,
                   upload.actual_sha256,
                   upload.created_at,
                   upload.updated_at,
                   upload.finalized_at,
                   entry.reference_type,
                   resolution.status AS resolution_status,
                   resolution.matched_title AS resolved_title,
                   resolution.matched_authors::text AS resolved_authors,
                   resolution.matched_year AS resolved_year,
                   resolution.matched_doi AS resolved_doi,
                   run.configuration_snapshot::text AS configuration_snapshot
              FROM recovery_batch_uploads upload
              JOIN recovery_batches batch ON batch.id = upload.batch_id
              JOIN analysis_runs run ON run.id = upload.analysis_run_id
              JOIN source_documents document ON document.id = run.document_id
              JOIN bibliography_entries entry
                ON entry.analysis_run_id = upload.analysis_run_id
               AND entry.id = upload.bibliography_entry_id
              LEFT JOIN bibliography_entry_resolutions resolution
                ON resolution.analysis_run_id = upload.analysis_run_id
               AND resolution.bibliography_entry_id = upload.bibliography_entry_id
             WHERE batch.id = ?
               AND upload.id = ?
               AND batch.status = 'OPEN'
               AND batch.expires_at > ?
               AND upload.status = 'STAGED'
               AND NOT EXISTS (
                   SELECT 1 FROM source_document_tombstones tombstone
                    WHERE tombstone.document_id = document.id
               )
            """.trimIndent(),
            { rs, _ ->
                val upload = RecoveryUpload(
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
                val resolvedReference = rs.getString("resolution_status")
                    .takeIf { it == "RESOLVED" }
                    ?.let {
                        BibliographyReference(
                            title = rs.getString("resolved_title"),
                            authors = JsonUtil.fromJson(
                                rs.getString("resolved_authors") ?: "[]",
                                JsonUtil.collectionType(List::class.java, String::class.java),
                            ),
                            year = rs.getObject("resolved_year", Integer::class.java)?.toInt(),
                            doi = rs.getString("resolved_doi"),
                            referenceType = rs.getString("reference_type"),
                        )
                    }
                RecoveryUploadValidationContext(
                    upload = upload,
                    referenceType = rs.getString("reference_type"),
                    resolvedReference = resolvedReference,
                    configuration = JsonUtil.fromJson(
                        rs.getString("configuration_snapshot"),
                        AnalysisConfigurationSnapshot::class.java,
                    ),
                )
            },
            batchId,
            uploadId,
            java.sql.Timestamp.from(now),
        ).firstOrNull()

    @Transactional
    fun record(attempt: RecoveryUploadValidationAttempt) {
        if (!lockActiveUpload(attempt.batchId, attempt.uploadId, attempt.analysisRunId, attempt.createdAt)) {
            throw RecoveryStagingException(
                "RECOVERY_UPLOAD_NOT_VALIDATABLE",
                409,
                "The Recovery Upload or its batch became inactive during validation.",
            )
        }
        jdbc.update(
            """
            INSERT INTO recovery_upload_validation_attempts (
                id, batch_id, upload_id, analysis_run_id, content_sha256,
                parser_id, parser_version, metadata_extraction_policy_version, identity_policy_version, parser_options,
                language_detector_id, language_detector_version, minimum_language_confidence,
                validation_status, identity_outcome, identity_reason_code, metadata_candidates,
                language_eligibility, detected_language, language_confidence, language_reason_code,
                failure_code, created_at
            ) VALUES (
                ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?
            )
            """.trimIndent(),
            attempt.id,
            attempt.batchId,
            attempt.uploadId,
            attempt.analysisRunId,
            attempt.contentSha256,
            attempt.parserId,
            attempt.parserVersion,
            attempt.metadataExtractionPolicyVersion,
            attempt.identityPolicyVersion,
            JsonUtil.toJson(attempt.parserOptions),
            attempt.languageDetectorId,
            attempt.languageDetectorVersion,
            attempt.minimumLanguageConfidence,
            attempt.validationStatus.name,
            attempt.identityOutcome?.name,
            attempt.identityReasonCode,
            JsonUtil.toJson(attempt.metadataCandidates),
            attempt.languageEligibility.name,
            attempt.detectedLanguage,
            attempt.languageConfidence,
            attempt.languageReasonCode,
            attempt.failureCode,
            java.sql.Timestamp.from(attempt.createdAt),
        )
        touchBatch(attempt.batchId, attempt.createdAt)
    }

    private fun lockActiveUpload(batchId: UUID, uploadId: UUID, analysisRunId: UUID, now: Instant): Boolean = jdbc.query(
        """
        SELECT upload.id
          FROM recovery_batch_uploads upload
          JOIN recovery_batches batch ON batch.id = upload.batch_id
          JOIN analysis_runs run ON run.id = upload.analysis_run_id
          JOIN source_documents document ON document.id = run.document_id
         WHERE batch.id = ?
           AND upload.id = ?
           AND upload.analysis_run_id = ?
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
        analysisRunId,
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

    fun latestAttempt(batchId: UUID, uploadId: UUID, now: Instant): RecoveryUploadValidationAttempt? =
        jdbc.query(
            """
            SELECT attempt.*
              FROM recovery_upload_validation_attempts attempt
              JOIN recovery_batch_uploads upload ON upload.id = attempt.upload_id
              JOIN recovery_batches batch ON batch.id = attempt.batch_id
              JOIN analysis_runs run ON run.id = attempt.analysis_run_id
              JOIN source_documents document ON document.id = run.document_id
             WHERE attempt.batch_id = ?
               AND attempt.upload_id = ?
               AND upload.status = 'STAGED'
               AND batch.status = 'OPEN'
               AND batch.expires_at > ?
               AND NOT EXISTS (
                   SELECT 1 FROM source_document_tombstones tombstone
                    WHERE tombstone.document_id = document.id
               )
             ORDER BY attempt.created_at DESC, attempt.id DESC
             LIMIT 1
            """.trimIndent(),
            { rs, _ -> rs.toValidationAttempt() },
            batchId,
            uploadId,
            java.sql.Timestamp.from(now),
        ).firstOrNull()

    private fun java.sql.ResultSet.toValidationAttempt(): RecoveryUploadValidationAttempt = RecoveryUploadValidationAttempt(
        id = getObject("id", UUID::class.java),
        batchId = getObject("batch_id", UUID::class.java),
        uploadId = getObject("upload_id", UUID::class.java),
        analysisRunId = getObject("analysis_run_id", UUID::class.java),
        contentSha256 = getString("content_sha256"),
        parserId = getString("parser_id"),
        parserVersion = getString("parser_version"),
        metadataExtractionPolicyVersion = getString("metadata_extraction_policy_version"),
        identityPolicyVersion = getString("identity_policy_version"),
        parserOptions = JsonUtil.fromJson(getString("parser_options"), object : TypeReference<Map<String, String>>() {}),
        languageDetectorId = getString("language_detector_id"),
        languageDetectorVersion = getString("language_detector_version"),
        minimumLanguageConfidence = getDouble("minimum_language_confidence"),
        validationStatus = RecoveryValidationStatus.valueOf(getString("validation_status")),
        identityOutcome = getString("identity_outcome")?.let(RecoveryIdentityOutcome::valueOf),
        identityReasonCode = getString("identity_reason_code"),
        metadataCandidates = JsonUtil.fromJson(
            getString("metadata_candidates"),
            object : TypeReference<List<ParsedBibliographicMetadataCandidate>>() {},
        ),
        languageEligibility = RecoveryLanguageEligibility.valueOf(getString("language_eligibility")),
        detectedLanguage = getString("detected_language"),
        languageConfidence = getDouble("language_confidence").let { if (wasNull()) null else it },
        languageReasonCode = getString("language_reason_code"),
        failureCode = getString("failure_code"),
        createdAt = getTimestamp("created_at").toInstant(),
    )
}
