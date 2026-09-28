CREATE TABLE laya_evidence_passage_spans (
    id UUID PRIMARY KEY,
    evidence_candidate_id UUID NOT NULL,
    verification_id UUID NOT NULL,
    analysis_run_id UUID NOT NULL,
    bibliography_entry_id UUID NOT NULL,
    splitting_policy_version VARCHAR(80) NOT NULL,
    span_index INTEGER NOT NULL CONSTRAINT laya_evidence_passage_spans_index_nonnegative CHECK (span_index >= 0),
    core_start_offset INTEGER NOT NULL CONSTRAINT laya_evidence_passage_spans_core_start_nonnegative CHECK (core_start_offset >= 0),
    core_end_offset INTEGER NOT NULL CONSTRAINT laya_evidence_passage_spans_core_end_positive CHECK (core_end_offset > core_start_offset),
    context_start_offset INTEGER NOT NULL CONSTRAINT laya_evidence_passage_spans_context_start_nonnegative CHECK (context_start_offset >= 0),
    context_end_offset INTEGER NOT NULL CONSTRAINT laya_evidence_passage_spans_context_end_positive CHECK (context_end_offset > context_start_offset),
    token_counts JSONB NOT NULL CONSTRAINT laya_evidence_passage_spans_six_token_counts CHECK (
        jsonb_typeof(token_counts) = 'array' AND jsonb_array_length(token_counts) = 6
    ),
    system_one_provider VARCHAR(80) NOT NULL,
    system_one_model VARCHAR(160) NOT NULL,
    system_one_version VARCHAR(80) NOT NULL,
    judgement_rubric_version VARCHAR(80) NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('PENDING', 'COMPLETED', 'FAILED', 'INCOMPLETE')),
    failure_reason VARCHAR(64),
    judgement VARCHAR(32) CHECK (judgement IS NULL OR judgement IN (
        'DIRECT_SUPPORT', 'PARTIAL_SUPPORT', 'CONTRADICTS', 'UNRELATED', 'INSUFFICIENT'
    )),
    evidence_role VARCHAR(32) CHECK (evidence_role IS NULL OR evidence_role IN (
        'PRIMARY_FINDING', 'AUTHOR_SYNTHESIS', 'SECONDARY_REPORT'
    )),
    confidence DOUBLE PRECISION CHECK (confidence IS NULL OR confidence BETWEEN 0 AND 1),
    directness DOUBLE PRECISION CHECK (directness IS NULL OR directness BETWEEN 0 AND 1),
    claim_scope_match DOUBLE PRECISION CHECK (claim_scope_match IS NULL OR claim_scope_match BETWEEN 0 AND 1),
    study_design_quality DOUBLE PRECISION CHECK (study_design_quality IS NULL OR study_design_quality BETWEEN 0 AND 1),
    relevance DOUBLE PRECISION CHECK (relevance IS NULL OR relevance BETWEEN 0 AND 1),
    raw_scores JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT laya_evidence_passage_spans_idempotent_key
        UNIQUE (evidence_candidate_id, splitting_policy_version, span_index),
    CONSTRAINT laya_evidence_passage_spans_candidate_scope_fk
        FOREIGN KEY (evidence_candidate_id, verification_id, analysis_run_id, bibliography_entry_id)
        REFERENCES evidence_candidates(id, verification_id, analysis_run_id, bibliography_entry_id) ON DELETE CASCADE,
    CONSTRAINT laya_evidence_passage_spans_context_contains_core
        CHECK (context_start_offset <= core_start_offset AND context_end_offset >= core_end_offset),
    CONSTRAINT laya_evidence_passage_spans_status_shape
        CHECK (
        (status = 'PENDING' AND failure_reason IS NULL AND judgement IS NULL AND evidence_role IS NULL AND raw_scores IS NULL)
        OR (status = 'COMPLETED' AND failure_reason IS NULL AND judgement IS NOT NULL AND evidence_role IS NOT NULL AND raw_scores IS NOT NULL)
        OR (status IN ('FAILED', 'INCOMPLETE') AND failure_reason IS NOT NULL AND judgement IS NULL AND evidence_role IS NULL AND raw_scores IS NULL)
    )
);

CREATE INDEX laya_evidence_passage_spans_candidate_idx
    ON laya_evidence_passage_spans(evidence_candidate_id, splitting_policy_version, span_index);

CREATE INDEX laya_evidence_passage_spans_verification_idx
    ON laya_evidence_passage_spans(verification_id, span_index);

CREATE TRIGGER laya_evidence_passage_spans_require_active_source_document
    BEFORE INSERT OR UPDATE ON laya_evidence_passage_spans
    FOR EACH ROW EXECUTE FUNCTION reject_deleted_document_analysis_write();

CREATE FUNCTION enforce_laya_evidence_passage_span_transition() RETURNS trigger AS $$
BEGIN
    IF NEW.id IS DISTINCT FROM OLD.id
       OR NEW.evidence_candidate_id IS DISTINCT FROM OLD.evidence_candidate_id
       OR NEW.verification_id IS DISTINCT FROM OLD.verification_id
       OR NEW.analysis_run_id IS DISTINCT FROM OLD.analysis_run_id
       OR NEW.bibliography_entry_id IS DISTINCT FROM OLD.bibliography_entry_id
       OR NEW.splitting_policy_version IS DISTINCT FROM OLD.splitting_policy_version
       OR NEW.span_index IS DISTINCT FROM OLD.span_index
       OR NEW.core_start_offset IS DISTINCT FROM OLD.core_start_offset
       OR NEW.core_end_offset IS DISTINCT FROM OLD.core_end_offset
       OR NEW.context_start_offset IS DISTINCT FROM OLD.context_start_offset
       OR NEW.context_end_offset IS DISTINCT FROM OLD.context_end_offset
       OR NEW.token_counts IS DISTINCT FROM OLD.token_counts
       OR NEW.system_one_provider IS DISTINCT FROM OLD.system_one_provider
       OR NEW.system_one_model IS DISTINCT FROM OLD.system_one_model
       OR NEW.system_one_version IS DISTINCT FROM OLD.system_one_version
       OR NEW.judgement_rubric_version IS DISTINCT FROM OLD.judgement_rubric_version
       OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'Laya Evidence Passage span provenance is immutable';
    END IF;
    IF OLD.status IN ('COMPLETED', 'INCOMPLETE') THEN
        RAISE EXCEPTION 'Terminal Laya Evidence Passage spans are immutable';
    END IF;
    IF OLD.status = 'PENDING' AND NEW.status NOT IN ('PENDING', 'COMPLETED', 'FAILED') THEN
        RAISE EXCEPTION 'Invalid pending Laya Evidence Passage span transition';
    END IF;
    IF OLD.status = 'FAILED' AND NEW.status NOT IN ('PENDING', 'FAILED') THEN
        RAISE EXCEPTION 'Invalid failed Laya Evidence Passage span transition';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER laya_evidence_passage_spans_have_monotonic_status
    BEFORE UPDATE ON laya_evidence_passage_spans
    FOR EACH ROW EXECUTE FUNCTION enforce_laya_evidence_passage_span_transition();
