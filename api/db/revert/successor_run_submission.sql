DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM recovery_batches WHERE status = 'SUBMITTED')
       OR EXISTS (SELECT 1 FROM recovery_batch_successor_submissions)
       OR EXISTS (SELECT 1 FROM analysis_run_successor_work_manifest)
       OR EXISTS (SELECT 1 FROM analysis_run_successor_artifact_lineage)
       OR EXISTS (SELECT 1 FROM analysis_run_successor_asset_ownership) THEN
        RAISE EXCEPTION 'Cannot revert successor-run submission while persisted successor data exists';
    END IF;
END;
$$;

DROP TABLE IF EXISTS analysis_run_successor_asset_ownership;
DROP INDEX IF EXISTS analysis_run_successor_lineage_predecessor_idx;
DROP INDEX IF EXISTS analysis_run_successor_lineage_origin_idx;
DROP TABLE IF EXISTS analysis_run_successor_artifact_lineage;
DROP INDEX IF EXISTS analysis_run_successor_manifest_selected_upload_idx;
DROP TABLE IF EXISTS analysis_run_successor_work_manifest;
DROP INDEX IF EXISTS recovery_batch_successor_submissions_predecessor_idx;
DROP TABLE IF EXISTS recovery_batch_successor_submissions;
DROP TRIGGER IF EXISTS submitted_recovery_uploads_are_immutable ON recovery_batch_uploads;
DROP FUNCTION IF EXISTS enforce_submitted_recovery_upload_immutability();
ALTER TABLE recovery_batch_uploads
    DROP CONSTRAINT IF EXISTS recovery_batch_uploads_batch_id_id_key;

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
       AND NOT (OLD.status = 'OPEN' AND NEW.status = 'EXPIRED') THEN
        RAISE EXCEPTION 'Invalid Recovery Batch status transition: % -> %', OLD.status, NEW.status;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

ALTER TABLE recovery_batches
    DROP CONSTRAINT IF EXISTS recovery_batches_status_expiry_shape_check,
    ADD CONSTRAINT recovery_batches_check CHECK (
        (status = 'OPEN' AND expired_at IS NULL)
        OR (status = 'EXPIRED' AND expired_at IS NOT NULL)
    );

ALTER TABLE recovery_batches
    DROP CONSTRAINT IF EXISTS recovery_batches_status_check,
    ADD CONSTRAINT recovery_batches_status_check CHECK (status IN ('OPEN', 'EXPIRED'));
