CREATE TABLE canonical_papers (
    id UUID PRIMARY KEY,
    identity_key VARCHAR(512) NOT NULL UNIQUE,
    doi TEXT,
    title TEXT NOT NULL,
    authors JSONB NOT NULL DEFAULT '[]'::jsonb,
    publication_year INTEGER,
    metadata_provider_id VARCHAR(80) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (doi IS NULL OR doi ~* '^10\.[0-9]{4,9}/[-._;()/:A-Z0-9]+$')
);

CREATE UNIQUE INDEX canonical_papers_doi_unique
    ON canonical_papers (lower(doi))
    WHERE doi IS NOT NULL;

CREATE TABLE bibliography_entry_resolutions (
    analysis_run_id UUID NOT NULL,
    bibliography_entry_id UUID NOT NULL,
    status VARCHAR(40) NOT NULL CHECK (status IN ('RESOLVED', 'UNRESOLVED', 'UNSUPPORTED_REFERENCE_TYPE')),
    reason_code VARCHAR(64) NOT NULL,
    canonical_paper_id UUID REFERENCES canonical_papers(id),
    matched_doi TEXT,
    matched_title TEXT,
    matched_authors JSONB NOT NULL DEFAULT '[]'::jsonb,
    matched_year INTEGER,
    confidence_score DOUBLE PRECISION CHECK (confidence_score IS NULL OR confidence_score BETWEEN 0 AND 1),
    match_method VARCHAR(32),
    provider_id VARCHAR(80) NOT NULL,
    resolved_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (analysis_run_id, bibliography_entry_id),
    FOREIGN KEY (analysis_run_id, bibliography_entry_id)
        REFERENCES bibliography_entries(analysis_run_id, id) ON DELETE CASCADE,
    CHECK ((status = 'RESOLVED') = (canonical_paper_id IS NOT NULL))
);

CREATE INDEX bibliography_entry_resolutions_run_status_idx
    ON bibliography_entry_resolutions(analysis_run_id, status, bibliography_entry_id);

CREATE FUNCTION prevent_bibliography_resolution_update() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'Bibliography resolution results are immutable';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER bibliography_entry_resolutions_are_immutable
    BEFORE UPDATE ON bibliography_entry_resolutions
    FOR EACH ROW EXECUTE FUNCTION prevent_bibliography_resolution_update();
