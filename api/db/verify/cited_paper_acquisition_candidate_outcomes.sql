DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
          FROM information_schema.columns
         WHERE table_schema = 'public'
           AND table_name = 'cited_paper_access'
           AND column_name = 'candidate_outcomes'
           AND is_nullable = 'NO'
           AND column_default IS NOT NULL
    ) OR NOT EXISTS (
        SELECT 1
          FROM information_schema.columns
         WHERE table_schema = 'public'
           AND table_name = 'cited_paper_access'
           AND column_name = 'candidate_outcomes_omitted_count'
           AND is_nullable = 'NO'
           AND column_default IS NOT NULL
    ) OR NOT EXISTS (
        SELECT 1
          FROM pg_constraint
         WHERE conname = 'cited_paper_access_candidate_outcomes_check'
           AND conrelid = 'public.cited_paper_access'::regclass
           AND contype = 'c'
           AND convalidated
           AND pg_get_constraintdef(oid) LIKE '%jsonb_array_length(candidate_outcomes) <= 5%'
    ) OR NOT EXISTS (
        SELECT 1
          FROM pg_constraint
         WHERE conname = 'cited_paper_access_candidate_outcomes_omitted_count_check'
           AND conrelid = 'public.cited_paper_access'::regclass
    ) THEN
        RAISE EXCEPTION 'Bounded Cited Paper acquisition candidate outcomes are missing';
    END IF;
END;
$$;
