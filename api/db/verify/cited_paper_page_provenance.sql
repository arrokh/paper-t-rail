DO $$
BEGIN
    IF to_regclass('public.paper_chunk_page_numbers') IS NULL THEN
        RAISE EXCEPTION 'Cited Paper page locations are missing';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
         WHERE conrelid = 'public.cited_paper_access'::regclass
           AND conname = 'cited_paper_access_asset_run_unique'
           AND contype = 'u'
    ) THEN
        RAISE EXCEPTION 'Cited Paper PDF asset identifiers are not unique within their Analysis Run';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
         WHERE conrelid = 'public.paper_chunk_page_numbers'::regclass
           AND conname = 'paper_chunk_page_numbers_chunk_fk'
           AND contype = 'f'
    ) THEN
        RAISE EXCEPTION 'Cited Paper page locations are not scoped to their exact evidence chunks';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
         WHERE conrelid = 'public.paper_chunk_page_numbers'::regclass
           AND conname = 'paper_chunk_page_numbers_pkey'
           AND contype = 'p'
    ) THEN
        RAISE EXCEPTION 'Cited Paper page locations are not unique by Evidence Passage and page';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
         WHERE conrelid = 'public.paper_chunk_page_numbers'::regclass
           AND conname = 'paper_chunk_page_numbers_page_number_check'
           AND contype = 'c'
    ) THEN
        RAISE EXCEPTION 'Cited Paper page locations do not enforce positive physical page numbers';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_indexes
         WHERE schemaname = 'public' AND tablename = 'paper_chunk_page_numbers'
           AND indexname = 'paper_chunk_page_numbers_scope_idx'
    ) THEN
        RAISE EXCEPTION 'Cited Paper page location report index is missing';
    END IF;
END;
$$;
