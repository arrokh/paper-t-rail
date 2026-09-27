ALTER TABLE claim_paper_verifications
    ADD CONSTRAINT claim_paper_verifications_run_id_unique UNIQUE (analysis_run_id, id);

CREATE TABLE human_reviews (
    id UUID PRIMARY KEY,
    analysis_run_id UUID NOT NULL,
    verification_id UUID NOT NULL,
    action VARCHAR(16) NOT NULL CHECK (action IN ('AGREE', 'DISAGREE', 'OVERRIDE')),
    override_status VARCHAR(40) CHECK (override_status IS NULL OR override_status IN (
        'SUPPORTED', 'PARTIALLY_SUPPORTED', 'CONTRADICTED', 'INSUFFICIENT_EVIDENCE',
        'INACCESSIBLE', 'UNRESOLVED', 'UNSUPPORTED_REFERENCE_TYPE'
    )),
    note VARCHAR(2000),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT human_reviews_verification_fk
        FOREIGN KEY (analysis_run_id, verification_id)
        REFERENCES claim_paper_verifications(analysis_run_id, id) ON DELETE CASCADE,
    CONSTRAINT human_reviews_override_check
        CHECK ((action = 'OVERRIDE') = (override_status IS NOT NULL))
);

CREATE INDEX human_reviews_run_verification_created_idx
    ON human_reviews(analysis_run_id, verification_id, created_at, id);

CREATE FUNCTION prevent_human_review_mutation() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE' AND pg_trigger_depth() > 1 THEN
        RETURN OLD;
    END IF;
    RAISE EXCEPTION 'Human Reviews are append-only except for parent-verification deletion';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER human_reviews_are_append_only
    BEFORE UPDATE OR DELETE ON human_reviews
    FOR EACH ROW EXECUTE FUNCTION prevent_human_review_mutation();
