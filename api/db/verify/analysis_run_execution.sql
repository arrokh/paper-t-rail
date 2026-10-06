DO $$
BEGIN
    IF to_regclass('public.analysis_run_execution') IS NULL
       OR to_regclass('public.analysis_run_execution_spans') IS NULL
       OR to_regclass('public.analysis_run_execution_artifacts') IS NULL
       OR to_regclass('public.analysis_run_execution_span_artifacts') IS NULL THEN
        RAISE EXCEPTION 'Analysis Run execution tables are missing';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
         WHERE conrelid = 'analysis_run_execution_span_artifacts'::regclass
           AND confrelid = 'analysis_run_execution_artifacts'::regclass
           AND contype = 'f'
           AND cardinality(conkey) = 2
    ) THEN
        RAISE EXCEPTION 'Execution artifact association does not enforce run ownership';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_trigger
         WHERE tgname = 'analysis_run_execution_spans_require_active_source_document'
           AND NOT tgisinternal
    ) THEN
        RAISE EXCEPTION 'Execution span active-document trigger is missing';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_indexes
         WHERE schemaname = 'public' AND indexname = 'analysis_run_execution_spans_order_idx'
    ) THEN
        RAISE EXCEPTION 'Execution span ordering index is missing';
    END IF;
END;
$$;
