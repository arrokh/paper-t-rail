ALTER TABLE claim_paper_verifications
    DROP CONSTRAINT claim_paper_verifications_access_paper_fk,
    ALTER COLUMN canonical_paper_id DROP NOT NULL,
    ADD CONSTRAINT claim_paper_verifications_run_reference_fk
        FOREIGN KEY (analysis_run_id, bibliography_entry_id)
        REFERENCES bibliography_entries(analysis_run_id, id) ON DELETE CASCADE,
    ADD CONSTRAINT claim_paper_verifications_resolved_reference_fk
        FOREIGN KEY (analysis_run_id, bibliography_entry_id, canonical_paper_id)
        REFERENCES bibliography_entry_resolutions(analysis_run_id, bibliography_entry_id, canonical_paper_id) ON DELETE CASCADE,
    ADD COLUMN evidence_conflict BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN processing_failure_reason VARCHAR(64),
    ADD COLUMN aggregator_version VARCHAR(80);

DO $$
DECLARE
    check_name TEXT;
BEGIN
    FOR check_name IN
        SELECT conname
          FROM pg_constraint
         WHERE conrelid = 'claim_paper_verifications'::regclass
           AND contype = 'c'
           AND (
               pg_get_constraintdef(oid) ILIKE '%processing_status%'
               OR pg_get_constraintdef(oid) ILIKE '%final_status%'
           )
    LOOP
        EXECUTE format('ALTER TABLE claim_paper_verifications DROP CONSTRAINT %I', check_name);
    END LOOP;
END;
$$;

ALTER TABLE claim_paper_verifications
    ADD CONSTRAINT claim_paper_verifications_processing_status_check
        CHECK (processing_status IN ('PENDING', 'COMPLETED', 'FAILED')),
    ADD CONSTRAINT claim_paper_verifications_final_status_check
        CHECK (final_status IS NULL OR final_status IN (
            'SUPPORTED', 'PARTIALLY_SUPPORTED', 'CONTRADICTED', 'INSUFFICIENT_EVIDENCE',
            'INACCESSIBLE', 'UNRESOLVED', 'UNSUPPORTED_REFERENCE_TYPE'
        )),
    ADD CONSTRAINT claim_paper_verifications_completion_check
        CHECK ((processing_status = 'COMPLETED') = (final_status IS NOT NULL)),
    ADD CONSTRAINT claim_paper_verifications_failure_check
        CHECK ((processing_status = 'FAILED') = (processing_failure_reason IS NOT NULL)),
    ADD CONSTRAINT claim_paper_verifications_conflict_check
        CHECK (NOT evidence_conflict OR (processing_status = 'COMPLETED' AND final_status = 'INSUFFICIENT_EVIDENCE'));

CREATE OR REPLACE FUNCTION enforce_claim_paper_verification_transition() RETURNS trigger AS $$
BEGIN
    IF NEW.id IS DISTINCT FROM OLD.id
       OR NEW.analysis_run_id IS DISTINCT FROM OLD.analysis_run_id
       OR NEW.atomic_claim_id IS DISTINCT FROM OLD.atomic_claim_id
       OR NEW.bibliography_entry_id IS DISTINCT FROM OLD.bibliography_entry_id
       OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'Claim–Reference Verification identity is immutable';
    END IF;
    IF OLD.processing_status <> 'PENDING'
       OR NEW.processing_status NOT IN ('PENDING', 'COMPLETED', 'FAILED') THEN
        RAISE EXCEPTION 'Invalid Claim–Reference Verification transition';
    END IF;
    IF OLD.canonical_paper_id IS NOT NULL
       AND NEW.canonical_paper_id IS DISTINCT FROM OLD.canonical_paper_id THEN
        RAISE EXCEPTION 'Claim–Reference Verification Canonical Paper is immutable once resolved';
    END IF;
    IF OLD.verification_scope <> 'NONE'
       AND NEW.verification_scope IS DISTINCT FROM OLD.verification_scope THEN
        RAISE EXCEPTION 'Claim–Reference Verification scope is immutable once assigned';
    END IF;
    IF OLD.processing_failure_reason IS NOT NULL
       AND NEW.processing_failure_reason IS DISTINCT FROM OLD.processing_failure_reason THEN
        RAISE EXCEPTION 'Claim–Reference Verification failure reason is immutable';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER claim_paper_verifications_have_monotonic_status ON claim_paper_verifications;

CREATE TRIGGER claim_paper_verifications_have_monotonic_status
    BEFORE UPDATE ON claim_paper_verifications
    FOR EACH ROW EXECUTE FUNCTION enforce_claim_paper_verification_transition();

CREATE FUNCTION analysis_run_has_conflict_aware_evidence_coverage(configuration_snapshot JSONB)
RETURNS BOOLEAN
LANGUAGE SQL
IMMUTABLE
AS $$
    SELECT COALESCE(
        jsonb_typeof(configuration_snapshot -> 'openAccess') = 'object'
        AND configuration_snapshot #>> '{referenceResolution,executionStatus}' <> 'NOT_RUN'
        AND configuration_snapshot #>> '{aggregation,executionStatus}' = 'PENDING'
        AND jsonb_typeof(configuration_snapshot #> '{aggregation,thresholds}') = 'object',
        FALSE
    )
$$;

INSERT INTO claim_paper_verifications (
    id, analysis_run_id, atomic_claim_id, bibliography_entry_id,
    canonical_paper_id, processing_status, verification_scope
)
SELECT gen_random_uuid(), link.analysis_run_id, link.atomic_claim_id, target.bibliography_entry_id,
       NULL, 'PENDING', 'NONE'
  FROM atomic_claim_citation_targets link
  JOIN citation_targets target
    ON target.analysis_run_id = link.analysis_run_id
   AND target.id = link.citation_target_id
  JOIN analysis_runs run ON run.id = link.analysis_run_id
 WHERE analysis_run_has_conflict_aware_evidence_coverage(run.configuration_snapshot)
ON CONFLICT (analysis_run_id, atomic_claim_id, bibliography_entry_id) DO NOTHING;

ALTER TABLE evidence_candidates
    ADD CONSTRAINT evidence_candidates_id_scope_unique
        UNIQUE (id, verification_id, analysis_run_id, bibliography_entry_id);

CREATE TABLE evidence_judgements (
    id UUID PRIMARY KEY,
    evidence_candidate_id UUID NOT NULL UNIQUE,
    verification_id UUID NOT NULL,
    analysis_run_id UUID NOT NULL,
    bibliography_entry_id UUID NOT NULL,
    system_one_provider VARCHAR(80) NOT NULL,
    system_one_model VARCHAR(160),
    system_one_version VARCHAR(80) NOT NULL,
    judgement VARCHAR(32) NOT NULL CHECK (judgement IN (
        'DIRECT_SUPPORT', 'PARTIAL_SUPPORT', 'CONTRADICTS', 'UNRELATED', 'INSUFFICIENT'
    )),
    evidence_role VARCHAR(32) NOT NULL CHECK (evidence_role IN (
        'PRIMARY_FINDING', 'AUTHOR_SYNTHESIS', 'SECONDARY_REPORT'
    )),
    confidence DOUBLE PRECISION NOT NULL CHECK (confidence BETWEEN 0 AND 1),
    directness DOUBLE PRECISION NOT NULL CHECK (directness BETWEEN 0 AND 1),
    claim_scope_match DOUBLE PRECISION NOT NULL CHECK (claim_scope_match BETWEEN 0 AND 1),
    study_design_quality DOUBLE PRECISION NOT NULL CHECK (study_design_quality BETWEEN 0 AND 1),
    relevance DOUBLE PRECISION NOT NULL CHECK (relevance BETWEEN 0 AND 1),
    raw_scores JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT evidence_judgements_candidate_scope_fk
        FOREIGN KEY (evidence_candidate_id, verification_id, analysis_run_id, bibliography_entry_id)
        REFERENCES evidence_candidates(id, verification_id, analysis_run_id, bibliography_entry_id) ON DELETE CASCADE
);

CREATE INDEX evidence_judgements_verification_idx
    ON evidence_judgements(verification_id, evidence_candidate_id);

CREATE FUNCTION prevent_evidence_judgement_update() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'Evidence Judgements are immutable';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER evidence_judgements_are_immutable
    BEFORE UPDATE ON evidence_judgements
    FOR EACH ROW EXECUTE FUNCTION prevent_evidence_judgement_update();
