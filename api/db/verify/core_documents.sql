DO $$
BEGIN
    IF to_regclass('public.source_documents') IS NULL THEN
        RAISE EXCEPTION 'source_documents table is missing';
    END IF;
    IF to_regclass('public.analysis_runs') IS NULL THEN
        RAISE EXCEPTION 'analysis_runs table is missing';
    END IF;
    IF to_regclass('public.outbox_events') IS NULL OR to_regclass('public.inbox_events') IS NULL THEN
        RAISE EXCEPTION 'transactional outbox/inbox tables are missing';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_trigger WHERE tgname = 'source_documents_are_immutable') THEN
        RAISE EXCEPTION 'Source Document immutability trigger is missing';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_trigger WHERE tgname = 'analysis_run_provenance_and_status_are_guarded') THEN
        RAISE EXCEPTION 'Analysis Run provenance/status trigger is missing';
    END IF;
END;
$$;
