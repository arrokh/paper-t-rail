ALTER TABLE recovery_upload_asset_selections
    ADD CONSTRAINT recovery_upload_asset_selections_exact_attempt_version_key
        UNIQUE (batch_id, bibliography_entry_id, upload_id, validation_attempt_id, content_sha256);

CREATE TABLE analysis_run_successor_resolution_assistance (
    analysis_run_id UUID NOT NULL REFERENCES analysis_runs(id) ON DELETE CASCADE,
    successor_analysis_run_id UUID NOT NULL,
    predecessor_analysis_run_id UUID NOT NULL,
    batch_id UUID NOT NULL,
    bibliography_entry_id UUID NOT NULL,
    predecessor_bibliography_entry_id UUID NOT NULL,
    upload_id UUID NOT NULL,
    validation_attempt_id UUID NOT NULL,
    doi_confirmation_id UUID NOT NULL,
    content_sha256 VARCHAR(64) NOT NULL CHECK (content_sha256 ~ '^[0-9a-f]{64}$'),
    identifier_type VARCHAR(16) NOT NULL CHECK (identifier_type = 'DOI'),
    identifier_value TEXT NOT NULL CHECK (identifier_value ~ '^10\.[0-9]{4,9}/[^[:space:]]+$'),
    candidate_fingerprint VARCHAR(120) NOT NULL CHECK (candidate_fingerprint ~ '^recovery-metadata-candidate-v1:[0-9a-f]{64}$'),
    source_code VARCHAR(40) NOT NULL CHECK (source_code = 'HUMAN_CONFIRMED_PDF_DOI_CANDIDATE'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (successor_analysis_run_id, bibliography_entry_id),
    CHECK (analysis_run_id = successor_analysis_run_id),
    FOREIGN KEY (batch_id, predecessor_analysis_run_id, successor_analysis_run_id)
        REFERENCES recovery_batch_successor_submissions(batch_id, predecessor_analysis_run_id, successor_analysis_run_id)
        ON DELETE CASCADE,
    FOREIGN KEY (successor_analysis_run_id, bibliography_entry_id)
        REFERENCES analysis_run_successor_work_manifest(successor_analysis_run_id, bibliography_entry_id)
        ON DELETE CASCADE,
    FOREIGN KEY (predecessor_analysis_run_id, predecessor_bibliography_entry_id)
        REFERENCES bibliography_entries(analysis_run_id, id) ON DELETE CASCADE,
    FOREIGN KEY (batch_id, upload_id)
        REFERENCES recovery_batch_uploads(batch_id, id) ON DELETE CASCADE,
    FOREIGN KEY (batch_id, upload_id, validation_attempt_id, content_sha256)
        REFERENCES recovery_upload_validation_attempts(batch_id, upload_id, id, content_sha256) ON DELETE CASCADE,
    FOREIGN KEY (batch_id, predecessor_bibliography_entry_id, upload_id, validation_attempt_id, content_sha256)
        REFERENCES recovery_upload_asset_selections(batch_id, bibliography_entry_id, upload_id, validation_attempt_id, content_sha256)
        ON DELETE CASCADE,
    FOREIGN KEY (validation_attempt_id, doi_confirmation_id, content_sha256, identifier_type, identifier_value, candidate_fingerprint)
        REFERENCES recovery_upload_identifier_confirmations(validation_attempt_id, id, content_sha256, identifier_type, identifier_value, candidate_fingerprint)
        ON DELETE CASCADE,
    CHECK (predecessor_analysis_run_id <> successor_analysis_run_id)
);

CREATE FUNCTION prevent_successor_resolution_assistance_mutation() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE' AND pg_trigger_depth() > 1 THEN
        RETURN OLD;
    END IF;
    RAISE EXCEPTION 'Successor Resolution Assistance is immutable';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER successor_resolution_assistance_is_immutable
    BEFORE UPDATE OR DELETE ON analysis_run_successor_resolution_assistance
    FOR EACH ROW EXECUTE FUNCTION prevent_successor_resolution_assistance_mutation();

CREATE TRIGGER successor_resolution_assistance_requires_active_source_document
    BEFORE INSERT OR UPDATE ON analysis_run_successor_resolution_assistance
    FOR EACH ROW EXECUTE FUNCTION reject_deleted_document_analysis_write();
