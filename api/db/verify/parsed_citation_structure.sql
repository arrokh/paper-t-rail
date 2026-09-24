DO $$
BEGIN
    IF to_regclass('public.parsed_document_parses') IS NULL
       OR to_regclass('public.parsed_document_sections') IS NULL
       OR to_regclass('public.citation_contexts') IS NULL
       OR to_regclass('public.citation_occurrences') IS NULL
       OR to_regclass('public.bibliography_entries') IS NULL
       OR to_regclass('public.citation_targets') IS NULL THEN
        RAISE EXCEPTION 'Parsed citation structure tables are missing';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
         WHERE conname = 'analysis_runs_parse_provenance_unique'
           AND conrelid = 'public.analysis_runs'::regclass
    ) THEN
        RAISE EXCEPTION 'Analysis Run parser provenance constraint is missing';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
         WHERE conname = 'analysis_runs_status_check'
           AND conrelid = 'public.analysis_runs'::regclass
           AND pg_get_constraintdef(oid) LIKE '%PARSED%'
    ) THEN
        RAISE EXCEPTION 'Parsed Analysis Run status is not available';
    END IF;
    IF position('PARSED' IN pg_get_functiondef('public.enforce_analysis_run_provenance_and_transitions()'::regprocedure)) = 0 THEN
        RAISE EXCEPTION 'Analysis Run transitions do not allow the parsed state';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_trigger WHERE tgname = 'parsed_document_parses_are_immutable')
       OR NOT EXISTS (SELECT 1 FROM pg_trigger WHERE tgname = 'parsed_document_sections_are_immutable')
       OR NOT EXISTS (SELECT 1 FROM pg_trigger WHERE tgname = 'citation_contexts_are_immutable')
       OR NOT EXISTS (SELECT 1 FROM pg_trigger WHERE tgname = 'citation_occurrences_are_immutable')
       OR NOT EXISTS (SELECT 1 FROM pg_trigger WHERE tgname = 'bibliography_entries_are_immutable')
       OR NOT EXISTS (SELECT 1 FROM pg_trigger WHERE tgname = 'citation_targets_are_immutable') THEN
        RAISE EXCEPTION 'Parsed citation structure immutability triggers are missing';
    END IF;
END;
$$;
