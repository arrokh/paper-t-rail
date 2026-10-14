ALTER TABLE recovery_batches
    ADD COLUMN creation_rights_declaration_version VARCHAR(64);
UPDATE recovery_batches
   SET creation_rights_declaration_version = rights_declaration_version;

ALTER TABLE recovery_batches
    ALTER COLUMN rights_declaration_version DROP NOT NULL,
    ALTER COLUMN rights_declaration_text DROP NOT NULL,
    ALTER COLUMN rights_declared_at DROP NOT NULL,
    ADD CONSTRAINT recovery_batches_rights_snapshot_shape_check CHECK (
        (rights_declaration_version IS NULL AND rights_declaration_text IS NULL AND rights_declared_at IS NULL)
        OR (rights_declaration_version IS NOT NULL AND rights_declaration_text IS NOT NULL AND rights_declared_at IS NOT NULL)
    );

DROP INDEX recovery_batch_uploads_one_active_entry_idx;

CREATE OR REPLACE FUNCTION enforce_recovery_batch_provenance_and_status() RETURNS trigger AS $$
BEGIN
    IF NEW.id IS DISTINCT FROM OLD.id
       OR NEW.analysis_run_id IS DISTINCT FROM OLD.analysis_run_id
       OR NEW.idempotency_key IS DISTINCT FROM OLD.idempotency_key
       OR NEW.creation_rights_declaration_version IS DISTINCT FROM OLD.creation_rights_declaration_version
       OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'Recovery Batch identity provenance is immutable';
    END IF;

    IF OLD.rights_declaration_version IS NOT NULL
       AND (NEW.rights_declaration_version IS DISTINCT FROM OLD.rights_declaration_version
            OR NEW.rights_declaration_text IS DISTINCT FROM OLD.rights_declaration_text
            OR NEW.rights_declared_at IS DISTINCT FROM OLD.rights_declared_at) THEN
        RAISE EXCEPTION 'Recovery Batch declaration provenance is immutable';
    END IF;
    IF OLD.rights_declaration_version IS NULL
       AND NEW.rights_declaration_version IS NOT NULL
       AND (OLD.status <> 'OPEN' OR NEW.status <> 'OPEN') THEN
        RAISE EXCEPTION 'PDF rights declaration can be accepted only once while the batch is open';
    END IF;
    IF (NEW.rights_declaration_version IS NULL) <> (NEW.rights_declaration_text IS NULL)
       OR (NEW.rights_declaration_version IS NULL) <> (NEW.rights_declared_at IS NULL) THEN
        RAISE EXCEPTION 'Recovery Batch declaration snapshot must be complete';
    END IF;

    IF NEW.status IS DISTINCT FROM OLD.status
       AND NOT (OLD.status = 'OPEN' AND NEW.status IN ('EXPIRED', 'SUBMITTED')) THEN
        RAISE EXCEPTION 'Invalid Recovery Batch status transition: % -> %', OLD.status, NEW.status;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

ALTER TABLE recovery_batch_successor_submissions
    ADD COLUMN identifier_assistance_entry_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN resolution_queued_entry_count INTEGER NOT NULL DEFAULT 0;

ALTER TABLE recovery_batch_successor_submissions
    DROP CONSTRAINT recovery_batch_successor_submissions_selected_entry_count_check,
    ADD CONSTRAINT recovery_batch_successor_submissions_selected_entry_count_check CHECK (selected_entry_count >= 0),
    ADD CONSTRAINT recovery_batch_successor_submissions_identifier_count_check CHECK (identifier_assistance_entry_count >= 0),
    ADD CONSTRAINT recovery_batch_successor_submissions_resolution_count_check CHECK (resolution_queued_entry_count >= 0);

ALTER TABLE analysis_run_successor_work_manifest
    ADD COLUMN work_kind VARCHAR(24) NOT NULL DEFAULT 'RETAINED';
UPDATE analysis_run_successor_work_manifest
   SET work_kind = CASE WHEN disposition = 'REPROCESS' THEN 'PDF_ASSET' ELSE 'RETAINED' END;
ALTER TABLE analysis_run_successor_work_manifest
    ADD CONSTRAINT analysis_run_successor_work_manifest_work_kind_check
        CHECK (work_kind IN ('PDF_ASSET', 'IDENTITY_ONLY', 'RETAINED'));
DO $$
DECLARE constraint_row RECORD;
BEGIN
    FOR constraint_row IN
        SELECT conname
          FROM pg_constraint
         WHERE conrelid = 'analysis_run_successor_work_manifest'::regclass
           AND contype = 'c'
           AND pg_get_constraintdef(oid) LIKE '%selected_upload_id%'
    LOOP
        EXECUTE format('ALTER TABLE analysis_run_successor_work_manifest DROP CONSTRAINT %I', constraint_row.conname);
    END LOOP;
END;
$$;
ALTER TABLE analysis_run_successor_work_manifest
    ADD CONSTRAINT analysis_run_successor_work_manifest_selection_shape_check CHECK (
        (work_kind = 'PDF_ASSET' AND disposition = 'REPROCESS'
            AND selected_upload_id IS NOT NULL
            AND selected_content_sha256 IS NOT NULL
            AND selected_content_sha256 ~ '^[0-9a-f]{64}$')
        OR (work_kind = 'IDENTITY_ONLY' AND disposition = 'REPROCESS'
            AND selected_upload_id IS NULL AND selected_content_sha256 IS NULL)
        OR (work_kind = 'RETAINED' AND disposition = 'RETAIN'
            AND selected_upload_id IS NULL AND selected_content_sha256 IS NULL)
    );

CREATE TABLE recovery_batch_identifier_assistance (
    id UUID PRIMARY KEY,
    batch_id UUID NOT NULL REFERENCES recovery_batches(id) ON DELETE CASCADE,
    analysis_run_id UUID NOT NULL REFERENCES analysis_runs(id) ON DELETE CASCADE,
    bibliography_entry_id UUID NOT NULL,
    identifier_type VARCHAR(16) NOT NULL CHECK (identifier_type = 'DOI'),
    identifier_value TEXT NOT NULL CHECK (identifier_value ~ '^10[.][0-9]{4,9}/[-._;()/:a-z0-9]+$'),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    UNIQUE (batch_id, bibliography_entry_id),
    FOREIGN KEY (analysis_run_id, bibliography_entry_id)
        REFERENCES bibliography_entries(analysis_run_id, id) ON DELETE CASCADE,
    CHECK (updated_at >= created_at)
);

CREATE TABLE analysis_run_successor_doi_assistance (
    successor_analysis_run_id UUID NOT NULL,
    predecessor_analysis_run_id UUID NOT NULL,
    batch_id UUID NOT NULL,
    bibliography_entry_id UUID NOT NULL,
    predecessor_bibliography_entry_id UUID NOT NULL,
    identifier_type VARCHAR(16) NOT NULL CHECK (identifier_type = 'DOI'),
    identifier_value TEXT NOT NULL CHECK (identifier_value ~ '^10[.][0-9]{4,9}/[-._;()/:a-z0-9]+$'),
    source_code VARCHAR(48) NOT NULL CHECK (source_code = 'USER_CONFIRMED_DOI_NO_PDF'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (successor_analysis_run_id, bibliography_entry_id),
    FOREIGN KEY (batch_id, predecessor_analysis_run_id, successor_analysis_run_id)
        REFERENCES recovery_batch_successor_submissions(batch_id, predecessor_analysis_run_id, successor_analysis_run_id)
        ON DELETE CASCADE,
    FOREIGN KEY (successor_analysis_run_id, bibliography_entry_id)
        REFERENCES analysis_run_successor_work_manifest(successor_analysis_run_id, bibliography_entry_id)
        ON DELETE CASCADE,
    FOREIGN KEY (predecessor_analysis_run_id, predecessor_bibliography_entry_id)
        REFERENCES bibliography_entries(analysis_run_id, id) ON DELETE CASCADE,
    CHECK (successor_analysis_run_id <> predecessor_analysis_run_id)
);

CREATE FUNCTION prevent_successor_doi_assistance_mutation() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE' AND pg_trigger_depth() > 1 THEN
        RETURN OLD;
    END IF;
    RAISE EXCEPTION 'Successor DOI assistance is immutable';
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER successor_doi_assistance_is_immutable
    BEFORE UPDATE OR DELETE ON analysis_run_successor_doi_assistance
    FOR EACH ROW EXECUTE FUNCTION prevent_successor_doi_assistance_mutation();

CREATE TABLE recovery_history_events (
    append_sequence BIGINT GENERATED ALWAYS AS IDENTITY UNIQUE,
    id UUID PRIMARY KEY,
    batch_id UUID NOT NULL REFERENCES recovery_batches(id) ON DELETE CASCADE,
    analysis_run_id UUID NOT NULL REFERENCES analysis_runs(id) ON DELETE CASCADE,
    batch_status VARCHAR(16) NOT NULL CHECK (batch_status IN ('OPEN', 'EXPIRED', 'SUBMITTED')),
    event_type VARCHAR(40) NOT NULL CHECK (event_type IN (
        'BATCH_CREATED', 'RIGHTS_ACCEPTED', 'UPLOAD_INTENT_CREATED', 'UPLOAD_FINALIZED',
        'UPLOAD_REJECTED', 'UPLOAD_REMOVED', 'UPLOAD_EXPIRED', 'UPLOAD_DETAILS_PURGED',
        'VALIDATION_ATTEMPT_COMPLETED', 'EXACT_VERSION_CONFIRMED', 'UPLOAD_VERSION_SELECTED',
        'IDENTIFIER_ASSISTANCE_SET', 'IDENTIFIER_ASSISTANCE_REPLACED',
        'IDENTIFIER_ASSISTANCE_REMOVED', 'SUCCESSOR_SUBMITTED', 'WORK_REUSED',
        'WORK_RETAINED', 'WORK_QUEUED', 'WORK_SKIPPED', 'ATTEMPT_STARTED',
        'ATTEMPT_COMPLETED', 'ATTEMPT_FAILED'
    )),
    outcome_code VARCHAR(80) NOT NULL CHECK (outcome_code ~ '^[A-Z][A-Z0-9_]{0,79}$'),
    stage_code VARCHAR(64) CHECK (stage_code IS NULL OR stage_code ~ '^[a-z][a-z0-9_-]{0,63}$'),
    source_event_id UUID,
    attempt_number INTEGER CHECK (attempt_number IS NULL OR attempt_number > 0),
    bibliography_entry_id UUID,
    upload_id UUID,
    previous_upload_id UUID,
    successor_analysis_run_id UUID,
    successor_status VARCHAR(24) CHECK (successor_status IS NULL OR successor_status IN ('PROCESSING', 'COMPLETED', 'COMPLETED_WITH_WARNINGS', 'FAILED')),
    selected_entry_count INTEGER CHECK (selected_entry_count IS NULL OR selected_entry_count >= 0),
    identifier_assistance_entry_count INTEGER CHECK (identifier_assistance_entry_count IS NULL OR identifier_assistance_entry_count >= 0),
    resolution_queued_entry_count INTEGER CHECK (resolution_queued_entry_count IS NULL OR resolution_queued_entry_count >= 0),
    created_at TIMESTAMPTZ NOT NULL,
    FOREIGN KEY (analysis_run_id, bibliography_entry_id)
        REFERENCES bibliography_entries(analysis_run_id, id) ON DELETE CASCADE,
    CHECK ((event_type IN ('ATTEMPT_STARTED', 'ATTEMPT_COMPLETED', 'ATTEMPT_FAILED', 'VALIDATION_ATTEMPT_COMPLETED', 'EXACT_VERSION_CONFIRMED')) = (attempt_number IS NOT NULL)),
    CHECK ((event_type = 'SUCCESSOR_SUBMITTED') = (successor_analysis_run_id IS NOT NULL)),
    CHECK (event_type <> 'UPLOAD_VERSION_SELECTED' OR upload_id IS NOT NULL)
);
CREATE UNIQUE INDEX recovery_history_events_attempt_idempotency_idx
    ON recovery_history_events(source_event_id, event_type, stage_code, attempt_number)
    WHERE source_event_id IS NOT NULL;
CREATE INDEX recovery_history_events_run_cursor_idx
    ON recovery_history_events(analysis_run_id, append_sequence DESC);
CREATE INDEX recovery_history_events_batch_cursor_idx
    ON recovery_history_events(batch_id, append_sequence DESC);

CREATE FUNCTION prevent_recovery_history_event_mutation() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE' AND pg_trigger_depth() > 1 THEN
        RETURN OLD;
    END IF;
    RAISE EXCEPTION 'Recovery history events are append-only';
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER recovery_history_events_are_append_only
    BEFORE UPDATE OR DELETE ON recovery_history_events
    FOR EACH ROW EXECUTE FUNCTION prevent_recovery_history_event_mutation();
CREATE TRIGGER recovery_history_events_require_active_source_document
    BEFORE INSERT OR UPDATE ON recovery_history_events
    FOR EACH ROW EXECUTE FUNCTION reject_deleted_document_analysis_write();
CREATE TRIGGER recovery_batch_identifier_assistance_require_active_source_document
    BEFORE INSERT OR UPDATE ON recovery_batch_identifier_assistance
    FOR EACH ROW EXECUTE FUNCTION reject_deleted_document_analysis_write();
CREATE FUNCTION reject_deleted_document_successor_doi_assistance_write() RETURNS trigger AS $$
DECLARE
    source_document_id UUID;
BEGIN
    SELECT run.document_id
      INTO source_document_id
      FROM analysis_runs run
     WHERE run.id = NEW.predecessor_analysis_run_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Predecessor Analysis Run is unavailable';
    END IF;
    IF TG_OP = 'INSERT' THEN
        PERFORM 1 FROM source_documents WHERE id = source_document_id FOR UPDATE;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'Source Document has been deleted';
        END IF;
    ELSIF NOT EXISTS (SELECT 1 FROM source_documents WHERE id = source_document_id) THEN
        RAISE EXCEPTION 'Source Document has been deleted';
    END IF;
    IF EXISTS (SELECT 1 FROM source_document_tombstones WHERE document_id = source_document_id) THEN
        RAISE EXCEPTION 'Source Document has been deleted';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER successor_doi_assistance_requires_active_source_document
    BEFORE INSERT OR UPDATE ON analysis_run_successor_doi_assistance
    FOR EACH ROW EXECUTE FUNCTION reject_deleted_document_successor_doi_assistance_write();
