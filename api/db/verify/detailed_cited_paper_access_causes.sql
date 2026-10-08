DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
          FROM information_schema.columns
         WHERE table_schema = 'public'
           AND table_name = 'cited_paper_access'
           AND column_name = 'access_reasons'
           AND is_nullable = 'NO'
           AND column_default IS NOT NULL
    ) OR NOT EXISTS (
        SELECT 1
          FROM pg_constraint
         WHERE conname = 'cited_paper_access_access_reasons_check'
           AND conrelid = 'public.cited_paper_access'::regclass
    ) THEN
        RAISE EXCEPTION 'Detailed Cited Paper access causes are missing';
    END IF;
END;
$$;
