DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM evidence_judgements)
       OR EXISTS (
           SELECT 1 FROM claim_paper_verifications
            WHERE canonical_paper_id IS NULL
               OR processing_status = 'FAILED'
               OR aggregator_version IS NOT NULL
               OR evidence_conflict
       ) THEN
        RAISE EXCEPTION 'Cannot revert conflict-aware evidence coverage after it has persisted new verification results';
    END IF;
END;
$$;

DROP FUNCTION analysis_run_has_conflict_aware_evidence_coverage(JSONB);

DROP TRIGGER evidence_judgements_are_immutable ON evidence_judgements;
DROP FUNCTION prevent_evidence_judgement_update();
DROP TABLE evidence_judgements;
ALTER TABLE evidence_candidates
    DROP CONSTRAINT evidence_candidates_id_scope_unique;

DROP TRIGGER claim_paper_verifications_have_monotonic_status ON claim_paper_verifications;
DROP FUNCTION enforce_claim_paper_verification_transition();
ALTER TABLE claim_paper_verifications
    DROP CONSTRAINT claim_paper_verifications_processing_status_check,
    DROP CONSTRAINT claim_paper_verifications_final_status_check,
    DROP CONSTRAINT claim_paper_verifications_completion_check,
    DROP CONSTRAINT claim_paper_verifications_failure_check,
    DROP CONSTRAINT claim_paper_verifications_conflict_check,
    DROP CONSTRAINT claim_paper_verifications_run_reference_fk,
    DROP CONSTRAINT claim_paper_verifications_resolved_reference_fk,
    DROP COLUMN evidence_conflict,
    DROP COLUMN processing_failure_reason,
    DROP COLUMN aggregator_version,
    ALTER COLUMN canonical_paper_id SET NOT NULL,
    ADD CONSTRAINT claim_paper_verifications_access_paper_fk
        FOREIGN KEY (analysis_run_id, bibliography_entry_id, canonical_paper_id)
        REFERENCES cited_paper_access(analysis_run_id, bibliography_entry_id, canonical_paper_id) ON DELETE CASCADE,
    ADD CONSTRAINT claim_paper_verifications_processing_status_check
        CHECK (processing_status IN ('PENDING', 'COMPLETED')),
    ADD CONSTRAINT claim_paper_verifications_final_status_check
        CHECK (final_status IS NULL OR final_status IN (
            'SUPPORTED', 'PARTIALLY_SUPPORTED', 'CONTRADICTED', 'INSUFFICIENT_EVIDENCE',
            'INACCESSIBLE', 'UNRESOLVED', 'UNSUPPORTED_REFERENCE_TYPE'
        )),
    ADD CONSTRAINT claim_paper_verifications_completion_check
        CHECK ((processing_status = 'PENDING') = (final_status IS NULL));

CREATE FUNCTION enforce_claim_paper_verification_transition() RETURNS trigger AS $$
BEGIN
    IF NEW.id IS DISTINCT FROM OLD.id
       OR NEW.analysis_run_id IS DISTINCT FROM OLD.analysis_run_id
       OR NEW.atomic_claim_id IS DISTINCT FROM OLD.atomic_claim_id
       OR NEW.bibliography_entry_id IS DISTINCT FROM OLD.bibliography_entry_id
       OR NEW.canonical_paper_id IS DISTINCT FROM OLD.canonical_paper_id
       OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'Claim–Paper Verification provenance is immutable';
    END IF;
    IF OLD.processing_status <> 'PENDING' OR NEW.processing_status <> 'COMPLETED'
       OR NEW.final_status IS NULL OR NEW.verification_scope <> OLD.verification_scope THEN
        RAISE EXCEPTION 'Invalid Claim–Paper Verification transition';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER claim_paper_verifications_have_monotonic_status
    BEFORE UPDATE ON claim_paper_verifications
    FOR EACH ROW EXECUTE FUNCTION enforce_claim_paper_verification_transition();
