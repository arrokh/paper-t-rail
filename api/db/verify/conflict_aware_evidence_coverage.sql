DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
         WHERE conname = 'claim_paper_verifications_run_reference_fk'
           AND contype = 'f'
    ) OR NOT EXISTS (
        SELECT 1 FROM pg_constraint
         WHERE conname = 'claim_paper_verifications_resolved_reference_fk'
           AND contype = 'f'
    ) THEN
        RAISE EXCEPTION 'Claim–Reference Verifications are not constrained to run-scoped references and their resolved Canonical Paper';
    END IF;
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
         WHERE table_schema = 'public'
           AND table_name = 'claim_paper_verifications'
           AND column_name = 'canonical_paper_id'
           AND is_nullable <> 'YES'
    ) THEN
        RAISE EXCEPTION 'Unresolved references cannot be represented without an invented Canonical Paper';
    END IF;
    IF to_regclass('public.evidence_judgements') IS NULL THEN
        RAISE EXCEPTION 'Persisted Evidence Judgements table is missing';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
         WHERE conname = 'evidence_judgements_candidate_scope_fk'
           AND contype = 'f'
    ) OR NOT EXISTS (
        SELECT 1 FROM pg_constraint
         WHERE conname = 'claim_paper_verifications_completion_check'
           AND contype = 'c'
    ) OR NOT EXISTS (
        SELECT 1 FROM pg_constraint
         WHERE conname = 'claim_paper_verifications_failure_check'
           AND contype = 'c'
    ) OR NOT EXISTS (
        SELECT 1 FROM pg_constraint
         WHERE conname = 'claim_paper_verifications_final_status_check'
           AND contype = 'c'
    ) THEN
        RAISE EXCEPTION 'Judgement scope or incomplete-pair status constraints are missing';
    END IF;
    IF EXISTS (
        SELECT 1 FROM claim_paper_verifications
         WHERE (processing_status = 'COMPLETED') <> (final_status IS NOT NULL)
            OR (processing_status = 'FAILED') <> (processing_failure_reason IS NOT NULL)
    ) THEN
        RAISE EXCEPTION 'A Claim–Reference Verification incorrectly mixes processing and domain status';
    END IF;
    IF EXISTS (
        SELECT link.analysis_run_id, link.atomic_claim_id, target.bibliography_entry_id
          FROM atomic_claim_citation_targets link
          JOIN citation_targets target
            ON target.analysis_run_id = link.analysis_run_id
           AND target.id = link.citation_target_id
          JOIN analysis_runs run ON run.id = link.analysis_run_id
         WHERE jsonb_typeof(run.configuration_snapshot -> 'openAccess') = 'object'
           AND run.configuration_snapshot #>> '{referenceResolution,executionStatus}' <> 'NOT_RUN'
           AND run.configuration_snapshot #>> '{aggregation,executionStatus}' = 'PENDING'
           AND jsonb_typeof(run.configuration_snapshot #> '{aggregation,thresholds}') = 'object'
        EXCEPT
        SELECT analysis_run_id, atomic_claim_id, bibliography_entry_id
          FROM claim_paper_verifications
    ) THEN
        RAISE EXCEPTION 'An inferred Claim–Citation Target pair has no Claim–Reference Verification';
    END IF;
END;
$$;
