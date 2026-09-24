DO $$
BEGIN
    IF to_regclass('public.atomic_claims') IS NULL
       OR to_regclass('public.atomic_claim_citation_targets') IS NULL THEN
        RAISE EXCEPTION 'Atomic Claim persistence is missing';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
         WHERE table_schema = 'public'
           AND table_name = 'citation_targets'
           AND column_name = 'citation_context_id'
           AND is_nullable = 'NO'
    ) THEN
        RAISE EXCEPTION 'Citation Target context scope is missing';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
         WHERE conname = 'atomic_claims_run_context_source_span_unique'
           AND conrelid = 'public.atomic_claims'::regclass
    ) THEN
        RAISE EXCEPTION 'Atomic Claim source-span deduplication constraint is missing';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_trigger WHERE tgname = 'atomic_claim_source_span_is_context_scoped')
       OR NOT EXISTS (SELECT 1 FROM pg_trigger WHERE tgname = 'atomic_claims_are_immutable')
       OR NOT EXISTS (SELECT 1 FROM pg_trigger WHERE tgname = 'atomic_claim_citation_targets_are_immutable') THEN
        RAISE EXCEPTION 'Atomic Claim integrity or immutability trigger is missing';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
         WHERE conrelid = 'public.citation_targets'::regclass
           AND contype = 'f'
           AND conname = 'citation_targets_run_occurrence_context_fk'
    ) THEN
        RAISE EXCEPTION 'Citation Target context must match its Citation Occurrence context';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
         WHERE conrelid = 'public.atomic_claim_citation_targets'::regclass
           AND contype = 'f'
           AND conname = 'atomic_claim_target_claim_context_fk'
    ) OR NOT EXISTS (
        SELECT 1 FROM pg_constraint
         WHERE conrelid = 'public.atomic_claim_citation_targets'::regclass
           AND contype = 'f'
           AND conname = 'atomic_claim_target_target_context_fk'
    ) THEN
        RAISE EXCEPTION 'Same-context Claim–Citation Target constraints are missing';
    END IF;
END;
$$;
