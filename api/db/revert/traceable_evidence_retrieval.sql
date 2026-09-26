DROP TRIGGER cited_paper_indexing_has_monotonic_status ON cited_paper_indexing;
DROP FUNCTION enforce_cited_paper_indexing_transition();
DROP TABLE evidence_candidates;
DROP TABLE paper_chunk_embeddings;
DROP TABLE paper_chunks;
DROP TABLE cited_paper_indexing;
DROP TABLE cited_paper_parses;
ALTER TABLE claim_paper_verifications
    DROP CONSTRAINT claim_paper_verifications_report_scope_unique;
ALTER TABLE cited_paper_access
    DROP CONSTRAINT cited_paper_access_asset_content_unique;
ALTER TABLE cited_paper_access
    DROP COLUMN content_media_type;
ALTER TABLE cited_paper_access
    DROP CONSTRAINT cited_paper_access_asset_id_unique;
ALTER TABLE cited_paper_access
    DROP COLUMN asset_id;
