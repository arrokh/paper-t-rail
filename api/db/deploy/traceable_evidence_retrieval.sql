ALTER TABLE cited_paper_access
    ADD COLUMN asset_id UUID NOT NULL DEFAULT gen_random_uuid();

ALTER TABLE cited_paper_access
    ADD CONSTRAINT cited_paper_access_asset_id_unique UNIQUE (asset_id, analysis_run_id, bibliography_entry_id);

ALTER TABLE cited_paper_access
    ADD COLUMN content_media_type VARCHAR(120)
        CHECK (content_media_type IS NULL OR content_media_type IN ('application/pdf', 'text/plain'));

ALTER TABLE cited_paper_access
    ADD CONSTRAINT cited_paper_access_asset_content_unique
        UNIQUE (analysis_run_id, bibliography_entry_id, canonical_paper_id, content_sha256);

ALTER TABLE claim_paper_verifications
    ADD CONSTRAINT claim_paper_verifications_report_scope_unique
        UNIQUE (id, analysis_run_id, bibliography_entry_id);

CREATE TABLE cited_paper_parses (
    id UUID PRIMARY KEY,
    analysis_run_id UUID NOT NULL,
    bibliography_entry_id UUID NOT NULL,
    canonical_paper_id UUID NOT NULL,
    content_sha256 VARCHAR(64) NOT NULL CHECK (content_sha256 ~ '^[0-9a-f]{64}$'),
    parser_id VARCHAR(80) NOT NULL,
    parser_version VARCHAR(80) NOT NULL,
    language VARCHAR(16) NOT NULL CHECK (language = 'en'),
    language_detector_version VARCHAR(80) NOT NULL,
    normalized_text TEXT NOT NULL CHECK (length(normalized_text) > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (analysis_run_id, bibliography_entry_id),
    UNIQUE (id, analysis_run_id, bibliography_entry_id),
    CONSTRAINT cited_paper_parses_access_fk
        FOREIGN KEY (analysis_run_id, bibliography_entry_id, canonical_paper_id, content_sha256)
        REFERENCES cited_paper_access(analysis_run_id, bibliography_entry_id, canonical_paper_id, content_sha256)
        ON DELETE CASCADE
);

CREATE TABLE cited_paper_indexing (
    analysis_run_id UUID NOT NULL,
    bibliography_entry_id UUID NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('PENDING', 'COMPLETED', 'FAILED')),
    failure_reason VARCHAR(64),
    cited_paper_parse_id UUID,
    retrieval_profile_id VARCHAR(80) NOT NULL,
    vector_candidate_limit INTEGER NOT NULL CHECK (vector_candidate_limit > 0),
    lexical_candidate_limit INTEGER NOT NULL CHECK (lexical_candidate_limit > 0),
    final_candidate_limit INTEGER NOT NULL CHECK (final_candidate_limit > 0),
    reciprocal_rank_fusion_constant INTEGER NOT NULL CHECK (reciprocal_rank_fusion_constant > 0),
    embedding_provider VARCHAR(80) NOT NULL,
    embedding_model VARCHAR(160) NOT NULL,
    embedding_version VARCHAR(80) NOT NULL,
    embedding_dimension INTEGER NOT NULL CHECK (embedding_dimension >= 0),
    embedding_profile_hash VARCHAR(64) NOT NULL CHECK (embedding_profile_hash ~ '^[0-9a-f]{64}$'),
    candidate_count INTEGER NOT NULL DEFAULT 0 CHECK (candidate_count >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (analysis_run_id, bibliography_entry_id),
    CONSTRAINT cited_paper_indexing_access_fk
        FOREIGN KEY (analysis_run_id, bibliography_entry_id)
        REFERENCES cited_paper_access(analysis_run_id, bibliography_entry_id) ON DELETE CASCADE,
    CONSTRAINT cited_paper_indexing_parse_fk
        FOREIGN KEY (cited_paper_parse_id, analysis_run_id, bibliography_entry_id)
        REFERENCES cited_paper_parses(id, analysis_run_id, bibliography_entry_id),
    CHECK (
        (status = 'PENDING' AND failure_reason IS NULL AND cited_paper_parse_id IS NULL)
        OR (status = 'COMPLETED' AND failure_reason IS NULL AND cited_paper_parse_id IS NOT NULL)
        OR (status = 'FAILED' AND failure_reason IS NOT NULL)
    )
);

CREATE TABLE paper_chunks (
    id UUID PRIMARY KEY,
    cited_paper_parse_id UUID NOT NULL,
    analysis_run_id UUID NOT NULL,
    bibliography_entry_id UUID NOT NULL,
    chunk_order INTEGER NOT NULL CHECK (chunk_order >= 0),
    section_order INTEGER NOT NULL CHECK (section_order >= 0),
    section_heading TEXT,
    paragraph_start INTEGER NOT NULL CHECK (paragraph_start > 0),
    paragraph_end INTEGER NOT NULL CHECK (paragraph_end >= paragraph_start),
    page_number INTEGER CHECK (page_number IS NULL OR page_number > 0),
    text TEXT NOT NULL CHECK (length(text) > 0),
    text_search TSVECTOR GENERATED ALWAYS AS (to_tsvector('english'::regconfig, text)) STORED,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (cited_paper_parse_id, chunk_order),
    UNIQUE (id, analysis_run_id, bibliography_entry_id),
    CONSTRAINT paper_chunks_parse_fk
        FOREIGN KEY (cited_paper_parse_id, analysis_run_id, bibliography_entry_id)
        REFERENCES cited_paper_parses(id, analysis_run_id, bibliography_entry_id) ON DELETE CASCADE
);

CREATE INDEX paper_chunks_reference_scope_idx
    ON paper_chunks(analysis_run_id, bibliography_entry_id, cited_paper_parse_id, chunk_order);
CREATE INDEX paper_chunks_text_search_idx ON paper_chunks USING GIN(text_search);

CREATE TABLE paper_chunk_embeddings (
    id UUID PRIMARY KEY,
    paper_chunk_id UUID NOT NULL,
    analysis_run_id UUID NOT NULL,
    bibliography_entry_id UUID NOT NULL,
    provider_id VARCHAR(80) NOT NULL,
    model_id VARCHAR(160) NOT NULL,
    provider_version VARCHAR(80) NOT NULL,
    dimension INTEGER NOT NULL CHECK (dimension > 0),
    profile_hash VARCHAR(64) NOT NULL CHECK (profile_hash ~ '^[0-9a-f]{64}$'),
    embedding VECTOR NOT NULL CHECK (vector_dims(embedding) = dimension),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (paper_chunk_id, profile_hash),
    CONSTRAINT paper_chunk_embeddings_scope_profile_unique
        UNIQUE (paper_chunk_id, analysis_run_id, bibliography_entry_id, profile_hash),
    CONSTRAINT paper_chunk_embeddings_chunk_fk
        FOREIGN KEY (paper_chunk_id, analysis_run_id, bibliography_entry_id)
        REFERENCES paper_chunks(id, analysis_run_id, bibliography_entry_id) ON DELETE CASCADE
);

CREATE INDEX paper_chunk_embeddings_profile_scope_idx
    ON paper_chunk_embeddings(analysis_run_id, bibliography_entry_id, profile_hash, dimension);

CREATE TABLE evidence_candidates (
    id UUID PRIMARY KEY,
    verification_id UUID NOT NULL,
    paper_chunk_id UUID NOT NULL,
    analysis_run_id UUID NOT NULL,
    bibliography_entry_id UUID NOT NULL,
    profile_hash VARCHAR(64) NOT NULL CHECK (profile_hash ~ '^[0-9a-f]{64}$'),
    vector_rank INTEGER CHECK (vector_rank IS NULL OR vector_rank > 0),
    lexical_rank INTEGER CHECK (lexical_rank IS NULL OR lexical_rank > 0),
    fused_rank INTEGER NOT NULL CHECK (fused_rank > 0),
    fusion_score DOUBLE PRECISION NOT NULL CHECK (fusion_score >= 0),
    retrieval_profile_id VARCHAR(80) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (verification_id, paper_chunk_id),
    UNIQUE (verification_id, fused_rank),
    CONSTRAINT evidence_candidates_verification_scope_fk
        FOREIGN KEY (verification_id, analysis_run_id, bibliography_entry_id)
        REFERENCES claim_paper_verifications(id, analysis_run_id, bibliography_entry_id) ON DELETE CASCADE,
    CONSTRAINT evidence_candidates_chunk_scope_fk
        FOREIGN KEY (paper_chunk_id, analysis_run_id, bibliography_entry_id)
        REFERENCES paper_chunks(id, analysis_run_id, bibliography_entry_id) ON DELETE CASCADE,
    CONSTRAINT evidence_candidates_profile_fk
        FOREIGN KEY (paper_chunk_id, analysis_run_id, bibliography_entry_id, profile_hash)
        REFERENCES paper_chunk_embeddings(paper_chunk_id, analysis_run_id, bibliography_entry_id, profile_hash)
        ON DELETE CASCADE
);

CREATE INDEX evidence_candidates_verification_rank_idx
    ON evidence_candidates(verification_id, fused_rank);

CREATE FUNCTION enforce_cited_paper_indexing_transition() RETURNS trigger AS $$
BEGIN
    IF NEW.analysis_run_id IS DISTINCT FROM OLD.analysis_run_id
       OR NEW.bibliography_entry_id IS DISTINCT FROM OLD.bibliography_entry_id
       OR NEW.retrieval_profile_id IS DISTINCT FROM OLD.retrieval_profile_id
       OR NEW.vector_candidate_limit IS DISTINCT FROM OLD.vector_candidate_limit
       OR NEW.lexical_candidate_limit IS DISTINCT FROM OLD.lexical_candidate_limit
       OR NEW.final_candidate_limit IS DISTINCT FROM OLD.final_candidate_limit
       OR NEW.reciprocal_rank_fusion_constant IS DISTINCT FROM OLD.reciprocal_rank_fusion_constant
       OR NEW.embedding_provider IS DISTINCT FROM OLD.embedding_provider
       OR NEW.embedding_model IS DISTINCT FROM OLD.embedding_model
       OR NEW.embedding_version IS DISTINCT FROM OLD.embedding_version
       OR NEW.embedding_dimension IS DISTINCT FROM OLD.embedding_dimension
       OR NEW.embedding_profile_hash IS DISTINCT FROM OLD.embedding_profile_hash
       OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'Cited Paper retrieval provenance is immutable';
    END IF;
    IF OLD.status <> 'PENDING' OR NEW.status NOT IN ('COMPLETED', 'FAILED') THEN
        RAISE EXCEPTION 'Invalid Cited Paper indexing transition';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER cited_paper_indexing_has_monotonic_status
    BEFORE UPDATE ON cited_paper_indexing
    FOR EACH ROW EXECUTE FUNCTION enforce_cited_paper_indexing_transition();
