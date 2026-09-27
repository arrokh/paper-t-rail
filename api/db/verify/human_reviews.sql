DO $$
BEGIN
    IF to_regclass('public.human_reviews') IS NULL THEN
        RAISE EXCEPTION 'Human Review history table is missing';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
         WHERE conname = 'human_reviews_verification_fk'
           AND contype = 'f'
           AND confdeltype = 'c'
    ) OR NOT EXISTS (
        SELECT 1 FROM pg_constraint
         WHERE conname = 'human_reviews_override_check'
           AND contype = 'c'
    ) OR NOT EXISTS (
        SELECT 1 FROM pg_constraint
         WHERE conname = 'claim_paper_verifications_run_id_unique'
           AND contype = 'u'
    ) THEN
        RAISE EXCEPTION 'Human Reviews are not constrained to their exact Claim–Paper Verification or valid action/status pairs';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_trigger
         WHERE tgname = 'human_reviews_are_append_only'
           AND tgrelid = 'public.human_reviews'::regclass
           AND (tgtype & 27) = 27
           AND NOT tgisinternal
    ) THEN
        RAISE EXCEPTION 'Human Reviews can be modified after insertion';
    END IF;
END;
$$;
