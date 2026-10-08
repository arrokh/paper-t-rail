CREATE TABLE recovery_batches (
    id UUID PRIMARY KEY,
    analysis_run_id UUID NOT NULL REFERENCES analysis_runs(id) ON DELETE CASCADE,
    idempotency_key UUID NOT NULL,
    rights_declaration_version VARCHAR(64) NOT NULL,
    rights_declaration_text TEXT NOT NULL,
    rights_declared_at TIMESTAMPTZ NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'EXPIRED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_activity_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL,
    expired_at TIMESTAMPTZ,
    UNIQUE (analysis_run_id, id),
    UNIQUE (analysis_run_id, idempotency_key),
    CHECK ((status = 'OPEN' AND expired_at IS NULL) OR (status = 'EXPIRED' AND expired_at IS NOT NULL))
);

CREATE UNIQUE INDEX recovery_batches_one_open_per_run_idx
    ON recovery_batches (analysis_run_id)
    WHERE status = 'OPEN';

CREATE INDEX recovery_batches_expiry_idx
    ON recovery_batches (expires_at, id)
    WHERE status = 'OPEN';

CREATE TABLE recovery_batch_uploads (
    id UUID PRIMARY KEY,
    batch_id UUID NOT NULL,
    analysis_run_id UUID NOT NULL,
    bibliography_entry_id UUID NOT NULL,
    idempotency_key UUID NOT NULL,
    filename VARCHAR(120) NOT NULL,
    expected_size BIGINT NOT NULL CHECK (expected_size > 0),
    expected_sha256 VARCHAR(64) NOT NULL CHECK (expected_sha256 ~ '^[0-9a-f]{64}$'),
    staging_object_key TEXT NOT NULL UNIQUE,
    finalized_object_key TEXT NOT NULL UNIQUE,
    status VARCHAR(24) NOT NULL DEFAULT 'PENDING_UPLOAD'
        CHECK (status IN ('PENDING_UPLOAD', 'FINALIZING', 'STAGED', 'REJECTED', 'REMOVED', 'EXPIRED')),
    failure_code VARCHAR(64),
    presigned_url_expires_at TIMESTAMPTZ NOT NULL,
    actual_size BIGINT CHECK (actual_size IS NULL OR actual_size > 0),
    actual_sha256 VARCHAR(64) CHECK (actual_sha256 IS NULL OR actual_sha256 ~ '^[0-9a-f]{64}$'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    finalized_at TIMESTAMPTZ,
    removed_at TIMESTAMPTZ,
    UNIQUE (batch_id, idempotency_key),
    FOREIGN KEY (batch_id, analysis_run_id) REFERENCES recovery_batches(id, analysis_run_id) ON DELETE CASCADE,
    FOREIGN KEY (analysis_run_id, bibliography_entry_id) REFERENCES bibliography_entries(analysis_run_id, id) ON DELETE CASCADE,
    CHECK (
        (status = 'STAGED' AND actual_size IS NOT NULL AND actual_sha256 IS NOT NULL AND finalized_at IS NOT NULL)
        OR status <> 'STAGED'
    ),
    CHECK ((status = 'REJECTED' AND failure_code IS NOT NULL) OR status <> 'REJECTED')
);

CREATE UNIQUE INDEX recovery_batch_uploads_one_active_entry_idx
    ON recovery_batch_uploads (batch_id, bibliography_entry_id)
    WHERE status IN ('PENDING_UPLOAD', 'FINALIZING', 'STAGED');

CREATE INDEX recovery_batch_uploads_batch_idx
    ON recovery_batch_uploads (batch_id, created_at, id);

CREATE TABLE recovery_upload_cleanup_tombstones (
    id UUID PRIMARY KEY,
    object_key TEXT NOT NULL UNIQUE,
    not_before TIMESTAMPTZ NOT NULL,
    retry_until TIMESTAMPTZ NOT NULL,
    next_attempt_at TIMESTAMPTZ NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    last_success_at TIMESTAMPTZ,
    last_error_type VARCHAR(128),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (retry_until >= not_before)
);

CREATE INDEX recovery_upload_cleanup_due_idx
    ON recovery_upload_cleanup_tombstones (next_attempt_at, id);

CREATE FUNCTION enforce_recovery_batch_provenance_and_status() RETURNS trigger AS $$
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

CREATE FUNCTION enforce_recovery_upload_provenance_and_status() RETURNS trigger AS $$
BEGIN
    IF NEW.id IS DISTINCT FROM OLD.id
       OR NEW.batch_id IS DISTINCT FROM OLD.batch_id
       OR NEW.analysis_run_id IS DISTINCT FROM OLD.analysis_run_id
       OR NEW.bibliography_entry_id IS DISTINCT FROM OLD.bibliography_entry_id
       OR NEW.idempotency_key IS DISTINCT FROM OLD.idempotency_key
       OR NEW.filename IS DISTINCT FROM OLD.filename
       OR NEW.expected_size IS DISTINCT FROM OLD.expected_size
       OR NEW.expected_sha256 IS DISTINCT FROM OLD.expected_sha256
       OR NEW.staging_object_key IS DISTINCT FROM OLD.staging_object_key
       OR NEW.finalized_object_key IS DISTINCT FROM OLD.finalized_object_key
       OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'Recovery Upload metadata and object keys are immutable';
    END IF;

    IF NEW.status IS DISTINCT FROM OLD.status AND NOT (
        (OLD.status = 'PENDING_UPLOAD' AND NEW.status IN ('FINALIZING', 'REJECTED', 'REMOVED', 'EXPIRED'))
        OR (OLD.status = 'FINALIZING' AND NEW.status IN ('STAGED', 'REJECTED', 'REMOVED', 'EXPIRED'))
        OR (OLD.status = 'STAGED' AND NEW.status IN ('REMOVED', 'EXPIRED'))
        OR (OLD.status = 'REJECTED' AND NEW.status IN ('REMOVED', 'EXPIRED'))
    ) THEN
        RAISE EXCEPTION 'Invalid Recovery Upload status transition: % -> %', OLD.status, NEW.status;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER recovery_batches_provenance_and_status_guard
    BEFORE UPDATE ON recovery_batches
    FOR EACH ROW EXECUTE FUNCTION enforce_recovery_batch_provenance_and_status();

CREATE TRIGGER recovery_batch_uploads_provenance_and_status_guard
    BEFORE UPDATE ON recovery_batch_uploads
    FOR EACH ROW EXECUTE FUNCTION enforce_recovery_upload_provenance_and_status();

CREATE TRIGGER recovery_batches_require_active_source_document
    BEFORE INSERT OR UPDATE ON recovery_batches
    FOR EACH ROW EXECUTE FUNCTION reject_deleted_document_analysis_write();

CREATE TRIGGER recovery_batch_uploads_require_active_source_document
    BEFORE INSERT OR UPDATE ON recovery_batch_uploads
    FOR EACH ROW EXECUTE FUNCTION reject_deleted_document_analysis_write();
