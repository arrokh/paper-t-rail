DO $$
BEGIN
    IF to_regclass('public.cited_paper_access') IS NULL
       OR to_regclass('public.claim_paper_verifications') IS NULL THEN
        RAISE EXCEPTION 'Cited Paper access persistence is missing';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
         WHERE table_schema = 'public'
           AND table_name = 'cited_paper_access'
           AND column_name = 'access_status'
           AND is_nullable = 'NO'
    ) OR NOT EXISTS (
        SELECT 1 FROM information_schema.columns
         WHERE table_schema = 'public'
           AND table_name = 'cited_paper_access'
           AND column_name = 'language_detector_version'
    ) OR NOT EXISTS (
        SELECT 1 FROM information_schema.columns
         WHERE table_schema = 'public'
           AND table_name = 'cited_paper_access'
           AND column_name = 'access_reason'
    ) THEN
        RAISE EXCEPTION 'Cited Paper access outcomes or language provenance are missing';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
         WHERE table_schema = 'public'
           AND table_name = 'claim_paper_verifications'
           AND column_name = 'verification_scope'
    ) OR NOT EXISTS (
        SELECT 1 FROM information_schema.columns
         WHERE table_schema = 'public'
           AND table_name = 'claim_paper_verifications'
           AND column_name = 'final_status'
    ) THEN
        RAISE EXCEPTION 'Claim–Paper Verification state is missing';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
         WHERE conname = 'atomic_claims_run_id_unique'
           AND conrelid = 'public.atomic_claims'::regclass
    ) OR NOT EXISTS (
        SELECT 1 FROM pg_constraint
         WHERE conname = 'bibliography_entry_resolutions_run_paper_unique'
           AND conrelid = 'public.bibliography_entry_resolutions'::regclass
    ) OR NOT EXISTS (
        SELECT 1 FROM pg_constraint
         WHERE conname = 'cited_paper_access_run_paper_fk'
           AND conrelid = 'public.cited_paper_access'::regclass
    ) OR NOT EXISTS (
        SELECT 1 FROM pg_constraint
         WHERE conname = 'claim_paper_verifications_same_run_claim_fk'
           AND conrelid = 'public.claim_paper_verifications'::regclass
    ) OR NOT (
        EXISTS (
            SELECT 1 FROM pg_constraint
             WHERE conname = 'claim_paper_verifications_access_paper_fk'
               AND conrelid = 'public.claim_paper_verifications'::regclass
        ) OR (
            EXISTS (
                SELECT 1 FROM pg_constraint
                 WHERE conname = 'claim_paper_verifications_run_reference_fk'
                   AND conrelid = 'public.claim_paper_verifications'::regclass
            ) AND EXISTS (
                SELECT 1 FROM pg_constraint
                 WHERE conname = 'claim_paper_verifications_resolved_reference_fk'
                   AND conrelid = 'public.claim_paper_verifications'::regclass
            )
        )
    ) THEN
        RAISE EXCEPTION 'Same-run Cited Paper and Claim verification constraints are missing';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_trigger
         WHERE tgname = 'cited_paper_access_are_immutable'
           AND tgrelid = 'public.cited_paper_access'::regclass
           AND NOT tgisinternal
    ) OR NOT EXISTS (
        SELECT 1 FROM pg_trigger
         WHERE tgname = 'claim_paper_verifications_have_monotonic_status'
           AND tgrelid = 'public.claim_paper_verifications'::regclass
           AND NOT tgisinternal
    ) THEN
        RAISE EXCEPTION 'Cited Paper provenance immutability or verification transition guard is missing';
    END IF;
END;
$$;
