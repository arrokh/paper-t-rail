CREATE TABLE analysis_run_execution (
    analysis_run_id UUID PRIMARY KEY REFERENCES analysis_runs(id) ON DELETE CASCADE,
    recording_version INTEGER NOT NULL DEFAULT 1 CHECK (recording_version > 0),
    trace_id UUID NOT NULL,
    capture_requested BOOLEAN NOT NULL,
    capture_enabled BOOLEAN NOT NULL,
    recording_state VARCHAR(16) NOT NULL CHECK (recording_state IN ('RECORDING', 'STOPPED')),
    completeness VARCHAR(16) NOT NULL CHECK (completeness IN ('COMPLETE', 'INCOMPLETE', 'RECORDING')),
    gap_reason VARCHAR(120),
    started_at TIMESTAMPTZ NOT NULL,
    finished_at TIMESTAMPTZ,
    CHECK (NOT capture_enabled OR capture_requested),
    CHECK (finished_at IS NULL OR finished_at >= started_at)
);

CREATE TABLE analysis_run_execution_spans (
    id UUID PRIMARY KEY,
    analysis_run_id UUID NOT NULL REFERENCES analysis_run_execution(analysis_run_id) ON DELETE CASCADE,
    parent_span_id UUID,
    operation_id UUID NOT NULL,
    event_id UUID,
    stage_id VARCHAR(32) NOT NULL CHECK (stage_id IN ('source', 'references', 'access', 'evidence', 'verification')),
    kind VARCHAR(24) NOT NULL CHECK (kind IN ('INTERNAL', 'PROVIDER', 'QUEUE', 'PERSISTENCE', 'TRANSFORMATION')),
    name VARCHAR(120) NOT NULL,
    started_at TIMESTAMPTZ NOT NULL,
    ended_at TIMESTAMPTZ,
    duration_millis BIGINT CHECK (duration_millis >= 0),
    status VARCHAR(16) NOT NULL CHECK (status IN ('RUNNING', 'SUCCEEDED', 'FAILED', 'SKIPPED', 'REUSED', 'INTERRUPTED')),
    attempt INTEGER NOT NULL DEFAULT 1 CHECK (attempt > 0),
    provider_id VARCHAR(120),
    model_id VARCHAR(120),
    http_status INTEGER CHECK (http_status BETWEEN 100 AND 599),
    safe_error_code VARCHAR(120) CHECK (safe_error_code IS NULL OR safe_error_code ~ '^[A-Z][A-Z0-9_]{0,119}$'),
    attributes JSONB NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(attributes) = 'object'),
    UNIQUE (analysis_run_id, id),
    FOREIGN KEY (analysis_run_id, parent_span_id)
        REFERENCES analysis_run_execution_spans(analysis_run_id, id) ON DELETE CASCADE,
    CHECK ((ended_at IS NULL AND duration_millis IS NULL AND status = 'RUNNING')
        OR (ended_at IS NOT NULL AND duration_millis IS NOT NULL AND status <> 'RUNNING'))
);

CREATE INDEX analysis_run_execution_spans_order_idx
    ON analysis_run_execution_spans(analysis_run_id, started_at, id);
CREATE INDEX analysis_run_execution_spans_parent_idx
    ON analysis_run_execution_spans(analysis_run_id, parent_span_id, started_at, id);
CREATE INDEX analysis_run_execution_spans_operation_idx
    ON analysis_run_execution_spans(analysis_run_id, operation_id, attempt);

CREATE TABLE analysis_run_execution_artifacts (
    id UUID PRIMARY KEY,
    analysis_run_id UUID NOT NULL REFERENCES analysis_run_execution(analysis_run_id) ON DELETE CASCADE,
    content_sha256 VARCHAR(64) NOT NULL CHECK (content_sha256 ~ '^[0-9a-f]{64}$'),
    media_type VARCHAR(120) NOT NULL CHECK (media_type = 'application/json'),
    content TEXT,
    schema_version VARCHAR(80) NOT NULL,
    capture_version VARCHAR(80) NOT NULL,
    sanitizer_version VARCHAR(80) NOT NULL,
    size_bytes INTEGER NOT NULL CHECK (size_bytes BETWEEN 0 AND 1048576),
    removed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (analysis_run_id, content_sha256, schema_version),
    UNIQUE (analysis_run_id, id),
    CHECK ((content IS NULL AND removed_at IS NOT NULL) OR (content IS NOT NULL AND removed_at IS NULL)),
    CHECK (content IS NULL OR octet_length(content) = size_bytes)
);

CREATE TABLE analysis_run_execution_span_artifacts (
    analysis_run_id UUID NOT NULL,
    span_id UUID NOT NULL,
    artifact_id UUID,
    role VARCHAR(16) NOT NULL CHECK (role IN ('INPUT', 'REQUEST', 'RESPONSE', 'RESULT')),
    fidelity VARCHAR(16) NOT NULL CHECK (fidelity IN ('COMPLETE', 'SANITIZED', 'PARTIAL', 'OMITTED', 'REMOVED', 'UNAVAILABLE')),
    reason VARCHAR(120),
    schema_version VARCHAR(80),
    media_type VARCHAR(120),
    size_bytes INTEGER CHECK (size_bytes BETWEEN 0 AND 1048576),
    PRIMARY KEY (span_id, role),
    FOREIGN KEY (analysis_run_id, span_id)
        REFERENCES analysis_run_execution_spans(analysis_run_id, id) ON DELETE CASCADE,
    FOREIGN KEY (analysis_run_id, artifact_id)
        REFERENCES analysis_run_execution_artifacts(analysis_run_id, id) ON DELETE CASCADE,
    CHECK ((fidelity IN ('OMITTED', 'UNAVAILABLE') AND artifact_id IS NULL)
        OR (fidelity IN ('COMPLETE', 'SANITIZED', 'PARTIAL', 'REMOVED') AND artifact_id IS NOT NULL)),
    CHECK ((artifact_id IS NULL AND size_bytes IS NULL) OR (artifact_id IS NOT NULL AND size_bytes IS NOT NULL))
);

CREATE INDEX analysis_run_execution_artifacts_hash_idx
    ON analysis_run_execution_artifacts(analysis_run_id, content_sha256);
CREATE INDEX analysis_run_execution_span_artifacts_artifact_idx
    ON analysis_run_execution_span_artifacts(analysis_run_id, artifact_id);

CREATE TRIGGER analysis_run_execution_require_active_source_document
    BEFORE INSERT OR UPDATE ON analysis_run_execution
    FOR EACH ROW EXECUTE FUNCTION reject_deleted_document_analysis_write();
CREATE TRIGGER analysis_run_execution_spans_require_active_source_document
    BEFORE INSERT OR UPDATE ON analysis_run_execution_spans
    FOR EACH ROW EXECUTE FUNCTION reject_deleted_document_analysis_write();
CREATE TRIGGER analysis_run_execution_artifacts_require_active_source_document
    BEFORE INSERT OR UPDATE ON analysis_run_execution_artifacts
    FOR EACH ROW EXECUTE FUNCTION reject_deleted_document_analysis_write();
CREATE TRIGGER execution_span_artifacts_active_document_guard
    BEFORE INSERT OR UPDATE ON analysis_run_execution_span_artifacts
    FOR EACH ROW EXECUTE FUNCTION reject_deleted_document_analysis_write();
