DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
          FROM information_schema.columns
         WHERE table_schema = 'public'
           AND table_name = 'bibliography_entry_resolutions'
           AND column_name = 'candidate_evidence'
           AND data_type = 'jsonb'
           AND is_nullable = 'NO'
    ) THEN
        RAISE EXCEPTION 'Bibliography resolution candidate evidence column is missing or invalid';
    END IF;

    IF NOT EXISTS (
        SELECT 1
          FROM pg_constraint
         WHERE conname = 'bibliography_entry_resolutions_candidate_evidence_array_check'
           AND conrelid = 'public.bibliography_entry_resolutions'::regclass
    ) THEN
        RAISE EXCEPTION 'Bibliography resolution candidate evidence bound is missing';
    END IF;
END;
$$;
