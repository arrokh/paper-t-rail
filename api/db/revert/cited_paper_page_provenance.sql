DROP TABLE paper_chunk_page_numbers;

ALTER TABLE cited_paper_access
    DROP CONSTRAINT cited_paper_access_asset_run_unique;
