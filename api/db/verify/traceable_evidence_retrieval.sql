DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'vector') THEN
        RAISE EXCEPTION 'pgvector extension is missing';
    END IF;
    IF to_regclass('public.cited_paper_parses') IS NULL THEN
        RAISE EXCEPTION 'cited_paper_parses table is missing';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
         WHERE conname = 'cited_paper_access_asset_id_unique'
           AND contype = 'u'
    ) THEN
        RAISE EXCEPTION 'Version-pinned Cited Paper asset identity is missing';
    END IF;
    IF to_regclass('public.cited_paper_indexing') IS NULL THEN
        RAISE EXCEPTION 'cited_paper_indexing table is missing';
    END IF;
    IF to_regclass('public.paper_chunks') IS NULL OR to_regclass('public.paper_chunk_embeddings') IS NULL THEN
        RAISE EXCEPTION 'Cited Paper chunk or embedding table is missing';
    END IF;
    IF to_regclass('public.evidence_candidates') IS NULL THEN
        RAISE EXCEPTION 'evidence_candidates table is missing';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_indexes
         WHERE schemaname = 'public' AND tablename = 'paper_chunks'
           AND indexdef ILIKE '%USING gin (text_search)%'
    ) THEN
        RAISE EXCEPTION 'Paper chunk full-text search index is missing';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
         WHERE conname = 'evidence_candidates_verification_scope_fk'
           AND contype = 'f'
    ) OR NOT EXISTS (
        SELECT 1 FROM pg_constraint
         WHERE conname = 'evidence_candidates_chunk_scope_fk'
           AND contype = 'f'
    ) THEN
        RAISE EXCEPTION 'Evidence candidates are not constrained to the exact claim-reference and asset scope';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
         WHERE conname = 'paper_chunk_embeddings_scope_profile_unique'
           AND contype = 'u'
    ) THEN
        RAISE EXCEPTION 'Embedding profile uniqueness is missing';
    END IF;
END;
$$;
