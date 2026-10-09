CREATE TABLE recovery_upload_validation_attempts (
    id UUID PRIMARY KEY,
    batch_id UUID NOT NULL REFERENCES recovery_batches(id) ON DELETE CASCADE,
    upload_id UUID NOT NULL REFERENCES recovery_batch_uploads(id) ON DELETE CASCADE,
    analysis_run_id UUID NOT NULL REFERENCES analysis_runs(id) ON DELETE CASCADE,
    content_sha256 VARCHAR(64) NOT NULL CHECK (content_sha256 ~ '^[0-9a-f]{64}$'),
    parser_id VARCHAR(80) NOT NULL,
    parser_version VARCHAR(80) NOT NULL,
    metadata_extraction_policy_version VARCHAR(100) NOT NULL,
    parser_options JSONB NOT NULL,
    language_detector_id VARCHAR(80) NOT NULL,
    language_detector_version VARCHAR(80) NOT NULL,
    minimum_language_confidence DOUBLE PRECISION NOT NULL CHECK (minimum_language_confidence BETWEEN 0 AND 1),
    validation_status VARCHAR(16) NOT NULL CHECK (validation_status IN ('COMPLETED', 'FAILED')),
    identity_outcome VARCHAR(24) CHECK (identity_outcome IN ('VALIDATED', 'NEEDS_CONFIRMATION', 'MISMATCH')),
    identity_reason_code VARCHAR(64),
    metadata_candidates JSONB NOT NULL DEFAULT '[]'::jsonb,
    language_eligibility VARCHAR(16) NOT NULL CHECK (language_eligibility IN ('ELIGIBLE', 'INELIGIBLE', 'INDETERMINATE')),
    detected_language VARCHAR(16),
    language_confidence DOUBLE PRECISION CHECK (language_confidence IS NULL OR language_confidence BETWEEN 0 AND 1),
    language_reason_code VARCHAR(64) NOT NULL,
    failure_code VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (
        (validation_status = 'FAILED' AND identity_outcome IS NULL AND failure_code IS NOT NULL)
        OR (validation_status = 'COMPLETED' AND identity_outcome IS NOT NULL AND failure_code IS NULL)
    )
);

CREATE INDEX recovery_upload_validation_attempts_latest_idx
    ON recovery_upload_validation_attempts (upload_id, created_at DESC, id DESC);

CREATE FUNCTION prevent_recovery_upload_validation_attempt_update() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'Recovery Upload validation attempts are immutable';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER recovery_upload_validation_attempts_are_immutable
    BEFORE UPDATE ON recovery_upload_validation_attempts
    FOR EACH ROW EXECUTE FUNCTION prevent_recovery_upload_validation_attempt_update();
