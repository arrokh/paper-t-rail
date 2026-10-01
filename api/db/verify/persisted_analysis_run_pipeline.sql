DO $$
BEGIN
    IF to_regclass('public.analysis_run_pipeline_items') IS NULL THEN
        RAISE EXCEPTION 'Persisted Analysis Run pipeline items table is missing';
    END IF;
    IF NOT EXISTS (
        SELECT 1
          FROM pg_trigger
         WHERE tgname = 'analysis_run_pipeline_items_require_active_source_document'
           AND NOT tgisinternal
    ) THEN
        RAISE EXCEPTION 'Persisted Analysis Run pipeline items active-document trigger is missing';
    END IF;
END;
$$;
