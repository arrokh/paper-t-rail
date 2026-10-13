ALTER TABLE recovery_batches
    DROP CONSTRAINT recovery_batches_status_check,
    ADD CONSTRAINT recovery_batches_status_check CHECK (status IN ('OPEN', 'EXPIRED', 'SUBMITTED'));

ALTER TABLE recovery_batches
    DROP CONSTRAINT recovery_batches_check,
    ADD CONSTRAINT recovery_batches_status_expiry_shape_check CHECK (
        (status = 'OPEN' AND expired_at IS NULL)
        OR (status = 'EXPIRED' AND expired_at IS NOT NULL)
        OR (status = 'SUBMITTED' AND expired_at IS NULL)
    );

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

ALTER TABLE recovery_batch_uploads
    ADD CONSTRAINT recovery_batch_uploads_batch_id_id_key UNIQUE (batch_id, id);

CREATE FUNCTION enforce_submitted_recovery_upload_immutability() RETURNS trigger AS $$
BEGIN
    IF pg_trigger_depth() > 1 AND TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;

    IF EXISTS (
        SELECT 1 FROM recovery_batches
         WHERE id = OLD.batch_id AND status = 'SUBMITTED'
    ) THEN
        RAISE EXCEPTION 'Uploads in a submitted Recovery Batch are immutable';
    END IF;

    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER submitted_recovery_uploads_are_immutable
    BEFORE UPDATE OR DELETE ON recovery_batch_uploads
    FOR EACH ROW EXECUTE FUNCTION enforce_submitted_recovery_upload_immutability();

CREATE TABLE recovery_batch_successor_submissions (
    id UUID PRIMARY KEY,
    batch_id UUID NOT NULL UNIQUE,
    predecessor_analysis_run_id UUID NOT NULL,
    successor_analysis_run_id UUID NOT NULL UNIQUE REFERENCES analysis_runs(id) ON DELETE CASCADE,
    idempotency_key UUID NOT NULL,
    request_fingerprint VARCHAR(64) NOT NULL CHECK (request_fingerprint ~ '^[0-9a-f]{64}$'),
    plan_fingerprint VARCHAR(64) NOT NULL CHECK (plan_fingerprint ~ '^[0-9a-f]{64}$'),
    submitted_revision BIGINT NOT NULL CHECK (submitted_revision >= 0),
    configuration_snapshot JSONB NOT NULL CHECK (jsonb_typeof(configuration_snapshot) = 'object'),
    selected_entry_count INTEGER NOT NULL CHECK (selected_entry_count > 0),
    retained_entry_count INTEGER NOT NULL CHECK (retained_entry_count >= 0),
    queued_entry_count INTEGER NOT NULL CHECK (queued_entry_count >= 0 AND queued_entry_count <= selected_entry_count),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY (predecessor_analysis_run_id, batch_id)
        REFERENCES recovery_batches(analysis_run_id, id) ON DELETE CASCADE,
    UNIQUE (batch_id, idempotency_key),
    UNIQUE (successor_analysis_run_id, predecessor_analysis_run_id),
    UNIQUE (batch_id, predecessor_analysis_run_id, successor_analysis_run_id)
);

CREATE INDEX recovery_batch_successor_submissions_predecessor_idx
    ON recovery_batch_successor_submissions(predecessor_analysis_run_id, created_at DESC);

CREATE TABLE analysis_run_successor_work_manifest (
    successor_analysis_run_id UUID NOT NULL,
    predecessor_analysis_run_id UUID NOT NULL,
    batch_id UUID NOT NULL,
    bibliography_entry_id UUID NOT NULL,
    predecessor_bibliography_entry_id UUID NOT NULL,
    local_reference_key TEXT NOT NULL,
    disposition VARCHAR(16) NOT NULL CHECK (disposition IN ('REPROCESS', 'RETAIN')),
    selected_upload_id UUID,
    selected_content_sha256 VARCHAR(64),
    stage_dispositions JSONB NOT NULL CHECK (jsonb_typeof(stage_dispositions) = 'array'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (successor_analysis_run_id, bibliography_entry_id),
    FOREIGN KEY (batch_id, predecessor_analysis_run_id, successor_analysis_run_id)
        REFERENCES recovery_batch_successor_submissions(batch_id, predecessor_analysis_run_id, successor_analysis_run_id)
        ON DELETE CASCADE,
    FOREIGN KEY (successor_analysis_run_id, bibliography_entry_id)
        REFERENCES bibliography_entries(analysis_run_id, id) ON DELETE CASCADE,
    FOREIGN KEY (predecessor_analysis_run_id, predecessor_bibliography_entry_id)
        REFERENCES bibliography_entries(analysis_run_id, id) ON DELETE CASCADE,
    FOREIGN KEY (batch_id, selected_upload_id)
        REFERENCES recovery_batch_uploads(batch_id, id) ON DELETE CASCADE,
    CHECK (
        (disposition = 'REPROCESS'
            AND selected_upload_id IS NOT NULL
            AND selected_content_sha256 IS NOT NULL
            AND selected_content_sha256 ~ '^[0-9a-f]{64}$')
        OR (disposition = 'RETAIN'
            AND selected_upload_id IS NULL
            AND selected_content_sha256 IS NULL)
    )
);

CREATE UNIQUE INDEX analysis_run_successor_manifest_selected_upload_idx
    ON analysis_run_successor_work_manifest(batch_id, selected_upload_id)
    WHERE selected_upload_id IS NOT NULL;

CREATE TABLE analysis_run_successor_artifact_lineage (
    successor_analysis_run_id UUID NOT NULL,
    predecessor_analysis_run_id UUID NOT NULL,
    artifact_type VARCHAR(64) NOT NULL CHECK (artifact_type ~ '^[a-z][a-z0-9_-]{0,63}$'),
    successor_artifact_id UUID NOT NULL,
    predecessor_artifact_id UUID NOT NULL,
    originating_analysis_run_id UUID NOT NULL REFERENCES analysis_runs(id) ON DELETE CASCADE,
    originating_artifact_id UUID NOT NULL,
    originating_provider_id VARCHAR(120),
    originating_configuration_fingerprint VARCHAR(64)
        CHECK (originating_configuration_fingerprint IS NULL OR originating_configuration_fingerprint ~ '^[0-9a-f]{64}$'),
    originating_consent_fingerprint VARCHAR(64)
        CHECK (originating_consent_fingerprint IS NULL OR originating_consent_fingerprint ~ '^[0-9a-f]{64}$'),
    originating_data_categories JSONB NOT NULL DEFAULT '[]'::jsonb
        CHECK (jsonb_typeof(originating_data_categories) = 'array'),
    originating_retention_disclosure TEXT,
    reuse_reason_code VARCHAR(80) NOT NULL CHECK (reuse_reason_code ~ '^[A-Z][A-Z0-9_]{0,79}$'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (successor_analysis_run_id, artifact_type, successor_artifact_id),
    UNIQUE (successor_analysis_run_id, artifact_type, predecessor_artifact_id),
    FOREIGN KEY (successor_analysis_run_id, predecessor_analysis_run_id)
        REFERENCES recovery_batch_successor_submissions(successor_analysis_run_id, predecessor_analysis_run_id)
        ON DELETE CASCADE
);

CREATE INDEX analysis_run_successor_lineage_origin_idx
    ON analysis_run_successor_artifact_lineage(originating_analysis_run_id, artifact_type);

CREATE INDEX analysis_run_successor_lineage_predecessor_idx
    ON analysis_run_successor_artifact_lineage(predecessor_analysis_run_id, artifact_type);

CREATE TABLE analysis_run_successor_asset_ownership (
    successor_analysis_run_id UUID NOT NULL,
    predecessor_analysis_run_id UUID NOT NULL,
    batch_id UUID NOT NULL,
    bibliography_entry_id UUID NOT NULL,
    upload_id UUID NOT NULL,
    asset_id UUID NOT NULL,
    object_key TEXT NOT NULL,
    content_sha256 VARCHAR(64) NOT NULL CHECK (content_sha256 ~ '^[0-9a-f]{64}$'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (successor_analysis_run_id, bibliography_entry_id),
    UNIQUE (upload_id),
    FOREIGN KEY (batch_id, predecessor_analysis_run_id, successor_analysis_run_id)
        REFERENCES recovery_batch_successor_submissions(batch_id, predecessor_analysis_run_id, successor_analysis_run_id)
        ON DELETE CASCADE,
    FOREIGN KEY (batch_id, upload_id)
        REFERENCES recovery_batch_uploads(batch_id, id) ON DELETE CASCADE,
    FOREIGN KEY (asset_id, successor_analysis_run_id, bibliography_entry_id)
        REFERENCES cited_paper_access(asset_id, analysis_run_id, bibliography_entry_id) ON DELETE CASCADE
);

CREATE INDEX analysis_run_successor_asset_ownership_key_idx
    ON analysis_run_successor_asset_ownership(object_key, content_sha256);
