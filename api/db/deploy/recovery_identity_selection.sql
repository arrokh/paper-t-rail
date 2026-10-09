CREATE TABLE recovery_upload_identity_confirmations (
    id UUID PRIMARY KEY,
    batch_id UUID NOT NULL REFERENCES recovery_batches(id) ON DELETE CASCADE,
    upload_id UUID NOT NULL REFERENCES recovery_batch_uploads(id) ON DELETE CASCADE,
    analysis_run_id UUID NOT NULL REFERENCES analysis_runs(id) ON DELETE CASCADE,
    validation_attempt_id UUID NOT NULL UNIQUE REFERENCES recovery_upload_validation_attempts(id) ON DELETE CASCADE,
    content_sha256 VARCHAR(64) NOT NULL CHECK (content_sha256 ~ '^[0-9a-f]{64}$'),
    decision VARCHAR(32) NOT NULL CHECK (decision = 'CONFIRM_EXACT_VERSION'),
    confirmation_version VARCHAR(64) NOT NULL,
    confirmed_at TIMESTAMPTZ NOT NULL,
    CHECK (confirmation_version <> '')
);

CREATE INDEX recovery_upload_identity_confirmations_upload_idx
    ON recovery_upload_identity_confirmations (upload_id, confirmed_at DESC);

CREATE TABLE recovery_upload_asset_selections (
    id UUID PRIMARY KEY,
    batch_id UUID NOT NULL REFERENCES recovery_batches(id) ON DELETE CASCADE,
    analysis_run_id UUID NOT NULL REFERENCES analysis_runs(id) ON DELETE CASCADE,
    bibliography_entry_id UUID NOT NULL,
    upload_id UUID NOT NULL REFERENCES recovery_batch_uploads(id) ON DELETE CASCADE,
    validation_attempt_id UUID NOT NULL REFERENCES recovery_upload_validation_attempts(id) ON DELETE CASCADE,
    content_sha256 VARCHAR(64) NOT NULL CHECK (content_sha256 ~ '^[0-9a-f]{64}$'),
    selection_method VARCHAR(24) NOT NULL CHECK (selection_method IN ('MACHINE_VALIDATED', 'HUMAN_CONFIRMED')),
    selected_at TIMESTAMPTZ NOT NULL,
    FOREIGN KEY (analysis_run_id, bibliography_entry_id)
        REFERENCES bibliography_entries(analysis_run_id, id) ON DELETE CASCADE,
    UNIQUE (batch_id, bibliography_entry_id)
);

CREATE FUNCTION prevent_recovery_identity_confirmation_update() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'Recovery Upload identity confirmations are immutable';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER recovery_upload_identity_confirmations_are_immutable
    BEFORE UPDATE ON recovery_upload_identity_confirmations
    FOR EACH ROW EXECUTE FUNCTION prevent_recovery_identity_confirmation_update();

CREATE FUNCTION enforce_recovery_upload_identity_confirmation() RETURNS trigger AS $$
DECLARE
    attempt_batch_id UUID;
    attempt_upload_id UUID;
    attempt_analysis_run_id UUID;
    attempt_hash VARCHAR(64);
    attempt_status VARCHAR(16);
    attempt_outcome VARCHAR(24);
    upload_batch_id UUID;
    upload_status VARCHAR(24);
    upload_hash VARCHAR(64);
    upload_analysis_run_id UUID;
    batch_status VARCHAR(16);
    batch_expiry TIMESTAMPTZ;
    attempt_is_latest BOOLEAN;
BEGIN
    SELECT attempt.batch_id, attempt.upload_id, attempt.analysis_run_id, attempt.content_sha256,
           attempt.validation_status, attempt.identity_outcome, upload.batch_id, upload.status,
           upload.actual_sha256, upload.analysis_run_id, batch.status, batch.expires_at,
           (SELECT latest.id = attempt.id
              FROM recovery_upload_validation_attempts latest
             WHERE latest.upload_id = attempt.upload_id
             ORDER BY latest.created_at DESC, latest.id DESC
             LIMIT 1)
      INTO attempt_batch_id, attempt_upload_id, attempt_analysis_run_id, attempt_hash,
           attempt_status, attempt_outcome, upload_batch_id, upload_status, upload_hash, upload_analysis_run_id,
           batch_status, batch_expiry, attempt_is_latest
      FROM recovery_upload_validation_attempts attempt
      JOIN recovery_batch_uploads upload ON upload.id = attempt.upload_id
      JOIN recovery_batches batch ON batch.id = upload.batch_id
     WHERE attempt.id = NEW.validation_attempt_id;

    IF NOT FOUND
       OR NEW.batch_id IS DISTINCT FROM attempt_batch_id
       OR NEW.upload_id IS DISTINCT FROM attempt_upload_id
       OR NEW.analysis_run_id IS DISTINCT FROM attempt_analysis_run_id
       OR upload_batch_id IS DISTINCT FROM attempt_batch_id
       OR upload_analysis_run_id IS DISTINCT FROM attempt_analysis_run_id
       OR batch_status <> 'OPEN'
       OR batch_expiry <= now()
       OR NEW.content_sha256 IS DISTINCT FROM attempt_hash
       OR NEW.content_sha256 IS DISTINCT FROM upload_hash
       OR upload_status <> 'STAGED'
       OR attempt_status <> 'COMPLETED'
       OR NOT attempt_is_latest
       OR attempt_outcome <> 'NEEDS_CONFIRMATION'
       OR NEW.decision <> 'CONFIRM_EXACT_VERSION' THEN
        RAISE EXCEPTION 'Only a needs-confirmation attempt for the exact staged PDF can be human-confirmed';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER recovery_upload_identity_confirmations_validate
    BEFORE INSERT ON recovery_upload_identity_confirmations
    FOR EACH ROW EXECUTE FUNCTION enforce_recovery_upload_identity_confirmation();

CREATE FUNCTION enforce_recovery_upload_asset_selection() RETURNS trigger AS $$
DECLARE
    attempt_batch_id UUID;
    attempt_upload_id UUID;
    attempt_analysis_run_id UUID;
    attempt_entry_id UUID;
    upload_batch_id UUID;
    attempt_hash VARCHAR(64);
    attempt_status VARCHAR(16);
    attempt_outcome VARCHAR(24);
    attempt_language VARCHAR(16);
    upload_status VARCHAR(24);
    upload_hash VARCHAR(64);
    upload_analysis_run_id UUID;
    batch_status VARCHAR(16);
    batch_expiry TIMESTAMPTZ;
    has_confirmation BOOLEAN;
    attempt_is_latest BOOLEAN;
BEGIN
    SELECT attempt.batch_id, attempt.upload_id, attempt.analysis_run_id, upload.bibliography_entry_id,
           attempt.content_sha256, attempt.validation_status, attempt.identity_outcome,
           attempt.language_eligibility, upload.batch_id, upload.status, upload.actual_sha256,
           upload.analysis_run_id, batch.status, batch.expires_at,
           EXISTS (
               SELECT 1 FROM recovery_upload_identity_confirmations confirmation
                WHERE confirmation.validation_attempt_id = attempt.id
           ),
           (SELECT latest.id = attempt.id
              FROM recovery_upload_validation_attempts latest
             WHERE latest.upload_id = attempt.upload_id
             ORDER BY latest.created_at DESC, latest.id DESC
             LIMIT 1)
      INTO attempt_batch_id, attempt_upload_id, attempt_analysis_run_id, attempt_entry_id,
           attempt_hash, attempt_status, attempt_outcome, attempt_language,
           upload_batch_id, upload_status, upload_hash, upload_analysis_run_id, batch_status, batch_expiry,
           has_confirmation, attempt_is_latest
      FROM recovery_upload_validation_attempts attempt
      JOIN recovery_batch_uploads upload ON upload.id = attempt.upload_id
      JOIN recovery_batches batch ON batch.id = upload.batch_id
     WHERE attempt.id = NEW.validation_attempt_id;

    IF NOT FOUND
       OR NEW.batch_id IS DISTINCT FROM attempt_batch_id
       OR NEW.upload_id IS DISTINCT FROM attempt_upload_id
       OR NEW.analysis_run_id IS DISTINCT FROM attempt_analysis_run_id
       OR upload_batch_id IS DISTINCT FROM attempt_batch_id
       OR upload_analysis_run_id IS DISTINCT FROM attempt_analysis_run_id
       OR NEW.bibliography_entry_id IS DISTINCT FROM attempt_entry_id
       OR NEW.content_sha256 IS DISTINCT FROM attempt_hash
       OR NEW.content_sha256 IS DISTINCT FROM upload_hash
       OR upload_status <> 'STAGED'
       OR batch_status <> 'OPEN'
       OR batch_expiry <= now()
       OR attempt_status <> 'COMPLETED'
       OR NOT attempt_is_latest
       OR attempt_outcome = 'MISMATCH'
       OR attempt_outcome IS NULL
       OR attempt_language <> 'ELIGIBLE'
       OR (attempt_outcome = 'NEEDS_CONFIRMATION' AND (NOT has_confirmation OR NEW.selection_method <> 'HUMAN_CONFIRMED'))
       OR (attempt_outcome = 'VALIDATED' AND NEW.selection_method <> 'MACHINE_VALIDATED') THEN
        RAISE EXCEPTION 'Recovery Upload selection is not supported by its validation and confirmation state';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER recovery_upload_asset_selections_validate
    BEFORE INSERT ON recovery_upload_asset_selections
    FOR EACH ROW EXECUTE FUNCTION enforce_recovery_upload_asset_selection();

CREATE FUNCTION prevent_recovery_upload_asset_selection_update() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'Recovery Upload asset selections are immutable';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER recovery_upload_asset_selections_are_immutable
    BEFORE UPDATE ON recovery_upload_asset_selections
    FOR EACH ROW EXECUTE FUNCTION prevent_recovery_upload_asset_selection_update();

CREATE TRIGGER recovery_upload_identity_confirmations_require_active_source_document
    BEFORE INSERT OR UPDATE ON recovery_upload_identity_confirmations
    FOR EACH ROW EXECUTE FUNCTION reject_deleted_document_analysis_write();

CREATE TRIGGER recovery_upload_asset_selections_require_active_source_document
    BEFORE INSERT OR UPDATE ON recovery_upload_asset_selections
    FOR EACH ROW EXECUTE FUNCTION reject_deleted_document_analysis_write();

CREATE FUNCTION clear_recovery_upload_selection_after_new_validation() RETURNS trigger AS $$
BEGIN
    DELETE FROM recovery_upload_asset_selections
     WHERE upload_id = NEW.upload_id
       AND validation_attempt_id <> NEW.id;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER recovery_validation_attempts_clear_stale_selection
    AFTER INSERT ON recovery_upload_validation_attempts
    FOR EACH ROW EXECUTE FUNCTION clear_recovery_upload_selection_after_new_validation();
