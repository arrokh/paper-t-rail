package com.papertrail.api.analysis.successor.repository

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@Repository
class SuccessorSelectedAssetRepository(
    private val jdbc: JdbcTemplate,
) {
    fun selectedAsset(analysisRunId: UUID, bibliographyEntryId: UUID): SelectedSuccessorAsset? = jdbc.query(
        """
        SELECT manifest.predecessor_analysis_run_id,
               manifest.batch_id,
               manifest.predecessor_bibliography_entry_id,
               manifest.selected_content_sha256,
               upload.id AS upload_id,
               upload.status AS upload_status,
               upload.finalized_object_key,
               upload.actual_sha256,
               batch.analysis_run_id AS originating_analysis_run_id,
               batch.status AS batch_status,
               batch.rights_declaration_version,
               batch.rights_declaration_text,
               batch.rights_declared_at,
               selection.validation_attempt_id,
               selection.content_sha256 AS selection_content_sha256,
               selection.selection_method,
               attempt.validation_status,
               attempt.identity_outcome,
               attempt.content_sha256 AS attempt_content_sha256,
               attempt.language_eligibility,
               attempt.detected_language,
               attempt.language_detector_version,
               assistance.identifier_value AS assisted_doi,
               assistance.source_code AS assistance_source_code
          FROM analysis_run_successor_work_manifest manifest
          JOIN recovery_batches batch ON batch.id = manifest.batch_id
          JOIN recovery_batch_uploads upload
            ON upload.batch_id = manifest.batch_id AND upload.id = manifest.selected_upload_id
          LEFT JOIN recovery_upload_asset_selections selection
            ON selection.batch_id = manifest.batch_id
           AND selection.bibliography_entry_id = manifest.predecessor_bibliography_entry_id
           AND selection.upload_id = upload.id
          LEFT JOIN recovery_upload_validation_attempts attempt
            ON attempt.id = selection.validation_attempt_id
          LEFT JOIN analysis_run_successor_resolution_assistance assistance
            ON assistance.successor_analysis_run_id = manifest.successor_analysis_run_id
           AND assistance.bibliography_entry_id = manifest.bibliography_entry_id
         WHERE manifest.successor_analysis_run_id = ?
           AND manifest.bibliography_entry_id = ?
           AND manifest.disposition = 'REPROCESS'
           AND manifest.selected_upload_id IS NOT NULL
         FOR UPDATE OF manifest, batch, upload
        """.trimIndent(),
        { rs, _ ->
            SelectedSuccessorAsset(
                successorAnalysisRunId = analysisRunId,
                bibliographyEntryId = bibliographyEntryId,
                predecessorAnalysisRunId = rs.getObject("predecessor_analysis_run_id", UUID::class.java),
                batchId = rs.getObject("batch_id", UUID::class.java),
                predecessorBibliographyEntryId = rs.getObject("predecessor_bibliography_entry_id", UUID::class.java),
                uploadId = rs.getObject("upload_id", UUID::class.java),
                uploadStatus = rs.getString("upload_status"),
                objectKey = rs.getString("finalized_object_key"),
                selectedContentSha256 = rs.getString("selected_content_sha256"),
                actualSha256 = rs.getString("actual_sha256"),
                originatingAnalysisRunId = rs.getObject("originating_analysis_run_id", UUID::class.java),
                batchStatus = rs.getString("batch_status"),
                rightsDeclarationVersion = rs.getString("rights_declaration_version"),
                rightsDeclarationText = rs.getString("rights_declaration_text"),
                rightsDeclaredAt = rs.getTimestamp("rights_declared_at").toInstant(),
                validationAttemptId = rs.getObject("validation_attempt_id", UUID::class.java),
                selectionContentSha256 = rs.getString("selection_content_sha256"),
                selectionMethod = rs.getString("selection_method"),
                validationStatus = rs.getString("validation_status"),
                identityOutcome = rs.getString("identity_outcome"),
                attemptContentSha256 = rs.getString("attempt_content_sha256"),
                languageEligibility = rs.getString("language_eligibility"),
                detectedLanguage = rs.getString("detected_language"),
                languageDetectorVersion = rs.getString("language_detector_version"),
                assistedDoi = rs.getString("assisted_doi"),
                assistanceSourceCode = rs.getString("assistance_source_code"),
            )
        },
        analysisRunId,
        bibliographyEntryId,
    ).firstOrNull()

    fun ensureSelectedVerifications(
        analysisRunId: UUID,
        bibliographyEntryId: UUID,
        canonicalPaperId: UUID,
        now: Instant,
    ) {
        val conflictingCanonicalPaper = jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM claim_paper_verifications WHERE analysis_run_id = ? AND bibliography_entry_id = ? AND canonical_paper_id IS NOT NULL AND canonical_paper_id <> ?)",
            Boolean::class.java,
            analysisRunId,
            bibliographyEntryId,
            canonicalPaperId,
        ) == true
        check(!conflictingCanonicalPaper) { "Selected successor verification does not match the resolved Canonical Paper." }
        jdbc.update(
            "UPDATE claim_paper_verifications SET canonical_paper_id = ?, verification_scope = 'FULL_TEXT', updated_at = ? WHERE analysis_run_id = ? AND bibliography_entry_id = ? AND processing_status = 'PENDING' AND verification_scope = 'NONE' AND (canonical_paper_id IS NULL OR canonical_paper_id = ?)",
            canonicalPaperId, Timestamp.from(now), analysisRunId, bibliographyEntryId, canonicalPaperId,
        )
        jdbc.update(
            """
            INSERT INTO claim_paper_verifications (
                id, analysis_run_id, atomic_claim_id, bibliography_entry_id, canonical_paper_id,
                processing_status, verification_scope, created_at, updated_at
            )
            SELECT gen_random_uuid(), ?, claims.atomic_claim_id, ?, ?, 'PENDING', 'FULL_TEXT', ?, ?
              FROM (
                  SELECT DISTINCT link.atomic_claim_id
                    FROM atomic_claim_citation_targets link
                    JOIN citation_targets target
                      ON target.analysis_run_id = link.analysis_run_id
                     AND target.id = link.citation_target_id
                   WHERE link.analysis_run_id = ?
                     AND target.bibliography_entry_id = ?
              ) claims
            ON CONFLICT (analysis_run_id, atomic_claim_id, bibliography_entry_id) DO NOTHING
            """.trimIndent(),
            analysisRunId,
            bibliographyEntryId,
            canonicalPaperId,
            Timestamp.from(now),
            Timestamp.from(now),
            analysisRunId,
            bibliographyEntryId,
        )
    }

    fun insertOwnership(asset: SelectedSuccessorAsset): Boolean = jdbc.update(
        """
        INSERT INTO analysis_run_successor_asset_ownership (
            successor_analysis_run_id, predecessor_analysis_run_id, batch_id,
            bibliography_entry_id, upload_id, asset_id, object_key, content_sha256
        )
        SELECT ?, ?, ?, ?, ?, access.asset_id, ?, ?
          FROM cited_paper_access access
         WHERE access.analysis_run_id = ?
           AND access.bibliography_entry_id = ?
           AND access.access_origin = 'USER_SUPPLIED'
           AND access.object_key = ?
           AND access.content_sha256 = ?
           AND access.rights_declaration_version = ?
           AND access.rights_declaration_text = ?
           AND access.rights_declared_at = ?
           AND access.originating_analysis_run_id = ?
           AND access.originating_recovery_batch_id = ?
           AND access.originating_recovery_upload_id = ?
        ON CONFLICT (successor_analysis_run_id, bibliography_entry_id) DO NOTHING
        """.trimIndent(),
        asset.successorAnalysisRunId,
        asset.predecessorAnalysisRunId,
        asset.batchId,
        asset.bibliographyEntryId,
        asset.uploadId,
        asset.objectKey,
        asset.selectedContentSha256,
        asset.successorAnalysisRunId,
        asset.bibliographyEntryId,
        asset.objectKey,
        asset.selectedContentSha256,
        asset.rightsDeclarationVersion,
        asset.rightsDeclarationText,
        Timestamp.from(asset.rightsDeclaredAt),
        asset.originatingAnalysisRunId,
        asset.batchId,
        asset.uploadId,
    ) == 1
}

data class SelectedSuccessorAsset(
    val successorAnalysisRunId: UUID,
    val bibliographyEntryId: UUID,
    val predecessorAnalysisRunId: UUID,
    val batchId: UUID,
    val predecessorBibliographyEntryId: UUID,
    val uploadId: UUID,
    val uploadStatus: String?,
    val objectKey: String?,
    val selectedContentSha256: String?,
    val actualSha256: String?,
    val originatingAnalysisRunId: UUID,
    val batchStatus: String?,
    val rightsDeclarationVersion: String?,
    val rightsDeclarationText: String?,
    val rightsDeclaredAt: Instant,
    val validationAttemptId: UUID?,
    val selectionContentSha256: String?,
    val selectionMethod: String?,
    val validationStatus: String?,
    val identityOutcome: String?,
    val attemptContentSha256: String?,
    val languageEligibility: String?,
    val detectedLanguage: String?,
    val languageDetectorVersion: String?,
    val assistedDoi: String?,
    val assistanceSourceCode: String?,
)
