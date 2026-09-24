DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
          FROM pg_indexes
         WHERE schemaname = 'public'
           AND tablename = 'analysis_runs'
           AND indexname = 'analysis_runs_created_at_id_idx'
           AND indexdef LIKE '%(created_at DESC, id DESC)%'
    ) THEN
        RAISE EXCEPTION 'Analysis Run cursor ordering index is missing';
    END IF;
END;
$$;
