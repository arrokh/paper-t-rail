ALTER TABLE cited_paper_indexing
    DROP CONSTRAINT cited_paper_indexing_parse_fk,
    ADD CONSTRAINT cited_paper_indexing_parse_fk
        FOREIGN KEY (cited_paper_parse_id, analysis_run_id, bibliography_entry_id)
        REFERENCES cited_paper_parses(id, analysis_run_id, bibliography_entry_id);

DO $$
DECLARE
    table_name TEXT;
BEGIN
    FOREACH table_name IN ARRAY ARRAY[
        'outbox_events',
        'inbox_events',
        'parsed_document_parses',
        'parsed_document_sections',
        'citation_contexts',
        'citation_occurrences',
        'bibliography_entries',
        'citation_targets',
        'atomic_claims',
        'atomic_claim_citation_targets',
        'bibliography_entry_resolutions',
        'cited_paper_access',
        'claim_paper_verifications',
        'cited_paper_parses',
        'cited_paper_indexing',
        'paper_chunks',
        'paper_chunk_embeddings',
        'evidence_candidates',
        'evidence_judgements',
        'human_reviews'
    ] LOOP
        EXECUTE format('DROP TRIGGER %I ON %I', table_name || '_require_active_source_document', table_name);
    END LOOP;
END;
$$;

DROP TRIGGER analysis_runs_require_active_source_document ON analysis_runs;
DROP FUNCTION reject_deleted_document_analysis_write();
DROP FUNCTION reject_deleted_document_run_write();
DROP TRIGGER source_documents_cannot_be_recreated_after_deletion ON source_documents;
DROP FUNCTION reject_tombstoned_source_document_insert();
DROP INDEX inbox_events_analysis_run_idx;
ALTER TABLE inbox_events
    DROP CONSTRAINT inbox_events_analysis_run_id_fkey,
    DROP COLUMN analysis_run_id;
DROP TABLE source_document_tombstones;
