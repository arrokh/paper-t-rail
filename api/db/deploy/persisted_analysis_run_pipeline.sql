CREATE TABLE analysis_run_pipeline_items (
    analysis_run_id UUID NOT NULL REFERENCES analysis_runs(id) ON DELETE CASCADE,
    stage_id VARCHAR(32) NOT NULL CHECK (stage_id IN ('source', 'references', 'access', 'evidence', 'verification')),
    step_id VARCHAR(48) NOT NULL,
    item_id VARCHAR(100) NOT NULL,
    label VARCHAR(120) NOT NULL,
    status VARCHAR(24) NOT NULL CHECK (status IN ('WAITING', 'IN_PROGRESS', 'COMPLETED', 'SKIPPED', 'FAILED')),
    reason_code VARCHAR(120),
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (analysis_run_id, stage_id, step_id, item_id),
    CHECK (
        (stage_id = 'source' AND step_id = 'parse-document')
        OR (stage_id = 'references' AND step_id = 'resolve-entry')
        OR (stage_id = 'access' AND step_id = 'acquire-source')
        OR (stage_id = 'evidence' AND step_id = 'prepare-evidence')
        OR (stage_id = 'verification' AND step_id = 'assess-and-aggregate')
    )
);

CREATE TRIGGER analysis_run_pipeline_items_require_active_source_document
    BEFORE INSERT OR UPDATE ON analysis_run_pipeline_items
    FOR EACH ROW EXECUTE FUNCTION reject_deleted_document_analysis_write();
