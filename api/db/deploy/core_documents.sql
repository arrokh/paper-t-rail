CREATE TABLE source_documents (
    id UUID PRIMARY KEY,
    filename VARCHAR(120) NOT NULL,
    content_type VARCHAR(100) NOT NULL CHECK (content_type = 'application/pdf'),
    object_key TEXT NOT NULL UNIQUE,
    sha256 VARCHAR(64) NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    language VARCHAR(16) NOT NULL CHECK (language = 'en'),
    page_count INTEGER NOT NULL CHECK (page_count > 0),
    extracted_character_count INTEGER NOT NULL CHECK (extracted_character_count > 0),
    parser_id VARCHAR(80) NOT NULL,
    parser_version VARCHAR(80) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE analysis_runs (
    id UUID PRIMARY KEY,
    document_id UUID NOT NULL REFERENCES source_documents(id),
    source_content_sha256 VARCHAR(64) NOT NULL CHECK (source_content_sha256 ~ '^[0-9a-f]{64}$'),
    source_parser_id VARCHAR(80) NOT NULL,
    source_parser_version VARCHAR(80) NOT NULL,
    configuration_snapshot JSONB NOT NULL,
    status VARCHAR(32) NOT NULL CHECK (status IN (
        'QUEUED', 'PROCESSING', 'COMPLETED', 'COMPLETED_WITH_WARNINGS', 'FAILED'
    )),
    progress JSONB NOT NULL DEFAULT '{"stage":"QUEUED","percent":0,"message":"Waiting for worker."}',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    failure_reason TEXT
);

CREATE INDEX analysis_runs_document_created_idx
    ON analysis_runs(document_id, created_at DESC);

CREATE TABLE outbox_events (
    event_id UUID PRIMARY KEY,
    event_type VARCHAR(100) NOT NULL,
    schema_version INTEGER NOT NULL CHECK (schema_version > 0),
    analysis_run_id UUID NOT NULL REFERENCES analysis_runs(id),
    correlation_id UUID NOT NULL,
    causation_id UUID,
    occurred_at TIMESTAMPTZ NOT NULL,
    payload JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ,
    publish_attempts INTEGER NOT NULL DEFAULT 0 CHECK (publish_attempts >= 0)
);

CREATE INDEX outbox_events_unpublished_idx
    ON outbox_events(created_at, event_id) WHERE published_at IS NULL;

CREATE TABLE inbox_events (
    event_id UUID PRIMARY KEY,
    handler_name VARCHAR(120) NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE FUNCTION enforce_source_document_immutability() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'Source Document metadata is immutable';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER source_documents_are_immutable
    BEFORE UPDATE ON source_documents
    FOR EACH ROW EXECUTE FUNCTION enforce_source_document_immutability();

CREATE FUNCTION enforce_analysis_run_provenance_and_transitions() RETURNS trigger AS $$
BEGIN
    IF NEW.id IS DISTINCT FROM OLD.id
       OR NEW.document_id IS DISTINCT FROM OLD.document_id
       OR NEW.source_content_sha256 IS DISTINCT FROM OLD.source_content_sha256
       OR NEW.source_parser_id IS DISTINCT FROM OLD.source_parser_id
       OR NEW.source_parser_version IS DISTINCT FROM OLD.source_parser_version
       OR NEW.configuration_snapshot IS DISTINCT FROM OLD.configuration_snapshot
       OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'Analysis Run provenance is immutable';
    END IF;

    IF NEW.status IS DISTINCT FROM OLD.status AND NOT (
        (OLD.status = 'QUEUED' AND NEW.status IN ('PROCESSING', 'FAILED'))
        OR (OLD.status = 'PROCESSING' AND NEW.status IN ('COMPLETED', 'COMPLETED_WITH_WARNINGS', 'FAILED'))
    ) THEN
        RAISE EXCEPTION 'Invalid Analysis Run status transition: % -> %', OLD.status, NEW.status;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER analysis_run_provenance_and_status_are_guarded
    BEFORE UPDATE ON analysis_runs
    FOR EACH ROW EXECUTE FUNCTION enforce_analysis_run_provenance_and_transitions();
