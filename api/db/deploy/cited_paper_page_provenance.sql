ALTER TABLE cited_paper_access
    ADD CONSTRAINT cited_paper_access_asset_run_unique UNIQUE (asset_id, analysis_run_id);

CREATE TABLE paper_chunk_page_numbers (
    paper_chunk_id UUID NOT NULL,
    analysis_run_id UUID NOT NULL,
    bibliography_entry_id UUID NOT NULL,
    page_number INTEGER NOT NULL CHECK (page_number > 0),
    PRIMARY KEY (paper_chunk_id, page_number),
    CONSTRAINT paper_chunk_page_numbers_chunk_fk
        FOREIGN KEY (paper_chunk_id, analysis_run_id, bibliography_entry_id)
        REFERENCES paper_chunks(id, analysis_run_id, bibliography_entry_id) ON DELETE CASCADE
);

CREATE INDEX paper_chunk_page_numbers_scope_idx
    ON paper_chunk_page_numbers(analysis_run_id, bibliography_entry_id, paper_chunk_id, page_number);
