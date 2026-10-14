DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM recovery_batch_uploads
         WHERE status IN ('PENDING_UPLOAD', 'FINALIZING', 'STAGED')
         GROUP BY batch_id, bibliography_entry_id
        HAVING count(*) > 1
    ) THEN
        RAISE EXCEPTION 'Cannot restore one-active-upload constraint while multiple active versions exist';
    END IF;
    IF EXISTS (SELECT 1 FROM recovery_batches WHERE rights_declaration_version IS NULL OR rights_declaration_text IS NULL OR rights_declared_at IS NULL) THEN
        RAISE EXCEPTION 'Cannot restore required PDF-rights declaration while identifier-only batches exist';
    END IF;
    IF EXISTS (SELECT 1 FROM recovery_history_events)
       OR EXISTS (SELECT 1 FROM recovery_batch_identifier_assistance)
       OR EXISTS (SELECT 1 FROM analysis_run_successor_doi_assistance)
       OR EXISTS (SELECT 1 FROM recovery_batch_successor_submissions WHERE identifier_assistance_entry_count > 0 OR resolution_queued_entry_count > 0)
       OR EXISTS (SELECT 1 FROM analysis_run_successor_work_manifest WHERE work_kind = 'IDENTITY_ONLY') THEN
        RAISE EXCEPTION 'Cannot revert recovery history, including exact-version confirmation attempt provenance, or DOI assistance while new provenance exists';
    END IF;
END;
$$;

DROP TRIGGER IF EXISTS successor_doi_assistance_requires_active_source_document ON analysis_run_successor_doi_assistance;
DROP FUNCTION IF EXISTS reject_deleted_document_successor_doi_assistance_write();
DROP TRIGGER IF EXISTS recovery_batch_identifier_assistance_require_active_source_document ON recovery_batch_identifier_assistance;
DROP TRIGGER IF EXISTS recovery_history_events_require_active_source_document ON recovery_history_events;
DROP TRIGGER IF EXISTS recovery_history_events_are_append_only ON recovery_history_events;
DROP TRIGGER IF EXISTS successor_doi_assistance_is_immutable ON analysis_run_successor_doi_assistance;
DROP FUNCTION IF EXISTS prevent_recovery_history_event_mutation();
DROP FUNCTION IF EXISTS prevent_successor_doi_assistance_mutation();
DROP TABLE IF EXISTS recovery_history_events;
DROP TABLE IF EXISTS analysis_run_successor_doi_assistance;
DROP TABLE IF EXISTS recovery_batch_identifier_assistance;

ALTER TABLE analysis_run_successor_work_manifest
    DROP CONSTRAINT analysis_run_successor_work_manifest_selection_shape_check,
    DROP CONSTRAINT analysis_run_successor_work_manifest_work_kind_check;
ALTER TABLE analysis_run_successor_work_manifest
    ADD CONSTRAINT analysis_run_successor_work_manifest_selection_legacy_check CHECK (
        (disposition = 'REPROCESS'
            AND selected_upload_id IS NOT NULL
            AND selected_content_sha256 IS NOT NULL
            AND selected_content_sha256 ~ '^[0-9a-f]{64}$')
        OR (disposition = 'RETAIN'
            AND selected_upload_id IS NULL
            AND selected_content_sha256 IS NULL)
    ),
    DROP COLUMN work_kind;

ALTER TABLE recovery_batch_successor_submissions
    DROP CONSTRAINT recovery_batch_successor_submissions_resolution_count_check,
    DROP CONSTRAINT recovery_batch_successor_submissions_identifier_count_check,
    DROP CONSTRAINT recovery_batch_successor_submissions_selected_entry_count_check,
    DROP COLUMN resolution_queued_entry_count,
    DROP COLUMN identifier_assistance_entry_count,
    ADD CONSTRAINT recovery_batch_successor_submissions_selected_entry_count_check CHECK (selected_entry_count > 0);

CREATE OR REPLACE FUNCTION enforce_recovery_batch_provenance_and_status() RETURNS trigger AS $$
BEGIN
    IF NEW.id IS DISTINCT FROM OLD.id
       OR NEW.analysis_run_id IS DISTINCT FROM OLD.analysis_run_id
       OR NEW.idempotency_key IS DISTINCT FROM OLD.idempotency_key
       OR NEW.rights_declaration_version IS DISTINCT FROM OLD.rights_declaration_version
       OR NEW.rights_declaration_text IS DISTINCT FROM OLD.rights_declaration_text
       OR NEW.rights_declared_at IS DISTINCT FROM OLD.rights_declared_at
       OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'Recovery Batch declaration provenance is immutable';
    END IF;
    IF NEW.status IS DISTINCT FROM OLD.status
       AND NOT (OLD.status = 'OPEN' AND NEW.status IN ('EXPIRED', 'SUBMITTED')) THEN
        RAISE EXCEPTION 'Invalid Recovery Batch status transition: % -> %', OLD.status, NEW.status;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

ALTER TABLE recovery_batches
    DROP CONSTRAINT recovery_batches_rights_snapshot_shape_check,
    DROP COLUMN creation_rights_declaration_version,
    ALTER COLUMN rights_declaration_version SET NOT NULL,
    ALTER COLUMN rights_declaration_text SET NOT NULL,
    ALTER COLUMN rights_declared_at SET NOT NULL;

CREATE UNIQUE INDEX recovery_batch_uploads_one_active_entry_idx
    ON recovery_batch_uploads (batch_id, bibliography_entry_id)
    WHERE status IN ('PENDING_UPLOAD', 'FINALIZING', 'STAGED');
