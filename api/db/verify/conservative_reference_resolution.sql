DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.tables
         WHERE table_schema = 'public' AND table_name = 'canonical_papers'
    ) THEN
        RAISE EXCEPTION 'Canonical Paper identity table is missing';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.tables
         WHERE table_schema = 'public' AND table_name = 'bibliography_entry_resolutions'
    ) THEN
        RAISE EXCEPTION 'Bibliography resolution results table is missing';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_indexes
         WHERE schemaname = 'public' AND indexname = 'canonical_papers_doi_unique'
    ) THEN
        RAISE EXCEPTION 'Canonical DOI uniqueness index is missing';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_trigger
         WHERE tgname = 'bibliography_entry_resolutions_are_immutable'
           AND NOT tgisinternal
    ) THEN
        RAISE EXCEPTION 'Bibliography resolution immutability trigger is missing';
    END IF;
END;
$$;
