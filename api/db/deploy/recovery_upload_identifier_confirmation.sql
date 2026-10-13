ALTER TABLE recovery_upload_validation_attempts
    ADD CONSTRAINT recovery_upload_validation_attempts_batch_upload_version_key
        UNIQUE (batch_id, upload_id, id, content_sha256);

ALTER TABLE recovery_upload_identity_confirmations
    ADD CONSTRAINT recovery_upload_identity_confirmations_attempt_version_key
        UNIQUE (validation_attempt_id, id, content_sha256);

CREATE TABLE recovery_upload_identifier_confirmations (
    id UUID PRIMARY KEY,
    batch_id UUID NOT NULL,
    upload_id UUID NOT NULL,
    analysis_run_id UUID NOT NULL REFERENCES analysis_runs(id) ON DELETE CASCADE,
    validation_attempt_id UUID NOT NULL UNIQUE,
    identity_confirmation_id UUID NOT NULL,
    content_sha256 VARCHAR(64) NOT NULL CHECK (content_sha256 ~ '^[0-9a-f]{64}$'),
    identifier_type VARCHAR(16) NOT NULL CHECK (identifier_type = 'DOI'),
    identifier_value TEXT NOT NULL CHECK (identifier_value ~ '^10\.[0-9]{4,9}/[-._;()/:a-z0-9]+$'),
    candidate_fingerprint VARCHAR(120) NOT NULL
        CHECK (candidate_fingerprint ~ '^recovery-metadata-candidate-v1:[0-9a-f]{64}$'),
    confirmed_at TIMESTAMPTZ NOT NULL,
    UNIQUE (validation_attempt_id, id, content_sha256, identifier_type, identifier_value, candidate_fingerprint),
    FOREIGN KEY (batch_id, upload_id, validation_attempt_id, content_sha256)
        REFERENCES recovery_upload_validation_attempts(batch_id, upload_id, id, content_sha256) ON DELETE CASCADE,
    FOREIGN KEY (validation_attempt_id, identity_confirmation_id, content_sha256)
        REFERENCES recovery_upload_identity_confirmations(validation_attempt_id, id, content_sha256) ON DELETE CASCADE
);

CREATE FUNCTION enforce_recovery_upload_identifier_confirmation() RETURNS trigger AS $$
DECLARE
    attempt_batch_id UUID;
    attempt_upload_id UUID;
    attempt_analysis_run_id UUID;
    attempt_hash VARCHAR(64);
    attempt_status VARCHAR(16);
    attempt_outcome VARCHAR(24);
    upload_status VARCHAR(24);
    upload_hash VARCHAR(64);
    batch_status VARCHAR(16);
    batch_expiry TIMESTAMPTZ;
    attempt_is_latest BOOLEAN;
    exact_confirmation_decision VARCHAR(32);
BEGIN
    SELECT attempt.batch_id, attempt.upload_id, attempt.analysis_run_id, attempt.content_sha256,
           attempt.validation_status, attempt.identity_outcome, upload.status, upload.actual_sha256,
           batch.status, batch.expires_at,
           (SELECT latest.id = attempt.id
              FROM recovery_upload_validation_attempts latest
             WHERE latest.upload_id = attempt.upload_id
             ORDER BY latest.created_at DESC, latest.id DESC
             LIMIT 1),
           exact_confirmation.decision
      INTO attempt_batch_id, attempt_upload_id, attempt_analysis_run_id, attempt_hash,
           attempt_status, attempt_outcome, upload_status, upload_hash, batch_status, batch_expiry,
           attempt_is_latest, exact_confirmation_decision
      FROM recovery_upload_validation_attempts attempt
      JOIN recovery_batch_uploads upload ON upload.id = attempt.upload_id
      JOIN recovery_batches batch ON batch.id = upload.batch_id
      JOIN recovery_upload_identity_confirmations exact_confirmation
        ON exact_confirmation.validation_attempt_id = attempt.id
       AND exact_confirmation.id = NEW.identity_confirmation_id
       AND exact_confirmation.content_sha256 = attempt.content_sha256
     WHERE attempt.id = NEW.validation_attempt_id;

    IF NOT FOUND
       OR NEW.batch_id IS DISTINCT FROM attempt_batch_id
       OR NEW.upload_id IS DISTINCT FROM attempt_upload_id
       OR NEW.analysis_run_id IS DISTINCT FROM attempt_analysis_run_id
       OR NEW.content_sha256 IS DISTINCT FROM attempt_hash
       OR NEW.content_sha256 IS DISTINCT FROM upload_hash
       OR batch_status <> 'OPEN'
       OR batch_expiry <= now()
       OR upload_status <> 'STAGED'
       OR attempt_status <> 'COMPLETED'
       OR attempt_outcome <> 'NEEDS_CONFIRMATION'
       OR attempt_is_latest IS DISTINCT FROM TRUE
       OR exact_confirmation_decision <> 'CONFIRM_EXACT_VERSION'
       OR NEW.identifier_type <> 'DOI' THEN
        RAISE EXCEPTION 'A DOI candidate can be confirmed only for the exact current human-confirmed Recovery Upload validation attempt';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER recovery_upload_identifier_confirmations_validate
    BEFORE INSERT OR UPDATE ON recovery_upload_identifier_confirmations
    FOR EACH ROW EXECUTE FUNCTION enforce_recovery_upload_identifier_confirmation();

CREATE FUNCTION prevent_recovery_upload_identifier_confirmation_mutation() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE' AND pg_trigger_depth() > 1 THEN
        RETURN OLD;
    END IF;
    RAISE EXCEPTION 'Recovery Upload DOI candidate confirmations are immutable';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER recovery_upload_identifier_confirmations_are_immutable
    BEFORE UPDATE OR DELETE ON recovery_upload_identifier_confirmations
    FOR EACH ROW EXECUTE FUNCTION prevent_recovery_upload_identifier_confirmation_mutation();

CREATE TRIGGER recovery_upload_identifier_confirmations_require_active_source
    BEFORE INSERT OR UPDATE ON recovery_upload_identifier_confirmations
    FOR EACH ROW EXECUTE FUNCTION reject_deleted_document_analysis_write();
