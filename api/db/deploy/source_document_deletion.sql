CREATE TABLE source_document_tombstones (
    document_id UUID PRIMARY KEY,
    deleted_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE inbox_events
    ADD COLUMN analysis_run_id UUID;

UPDATE inbox_events inbox
   SET analysis_run_id = event.analysis_run_id
  FROM outbox_events event
 WHERE event.event_id = inbox.event_id;

DELETE FROM inbox_events WHERE analysis_run_id IS NULL;

ALTER TABLE inbox_events
    ALTER COLUMN analysis_run_id SET NOT NULL,
    ADD CONSTRAINT inbox_events_analysis_run_id_fkey
        FOREIGN KEY (analysis_run_id) REFERENCES analysis_runs(id) ON DELETE CASCADE;

CREATE INDEX inbox_events_analysis_run_idx ON inbox_events(analysis_run_id);

CREATE FUNCTION reject_tombstoned_source_document_insert() RETURNS trigger AS $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM source_document_tombstones WHERE document_id = NEW.id
    ) THEN
        RAISE EXCEPTION 'Source Document has been deleted';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER source_documents_cannot_be_recreated_after_deletion
    BEFORE INSERT ON source_documents
    FOR EACH ROW EXECUTE FUNCTION reject_tombstoned_source_document_insert();

CREATE FUNCTION reject_deleted_document_run_write() RETURNS trigger AS $$
DECLARE
    source_document_id UUID;
BEGIN
    source_document_id := NEW.document_id;
    IF TG_OP = 'INSERT' THEN
        PERFORM 1 FROM source_documents WHERE id = source_document_id FOR UPDATE;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'Source Document has been deleted';
        END IF;
    ELSIF NOT EXISTS (SELECT 1 FROM source_documents WHERE id = source_document_id) THEN
        RAISE EXCEPTION 'Source Document has been deleted';
    END IF;
    IF EXISTS (
        SELECT 1 FROM source_document_tombstones WHERE document_id = source_document_id
    ) THEN
        RAISE EXCEPTION 'Source Document has been deleted';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER analysis_runs_require_active_source_document
    BEFORE INSERT OR UPDATE ON analysis_runs
    FOR EACH ROW EXECUTE FUNCTION reject_deleted_document_run_write();

CREATE FUNCTION reject_deleted_document_analysis_write() RETURNS trigger AS $$
DECLARE
    source_document_id UUID;
BEGIN
    SELECT run.document_id
      INTO source_document_id
      FROM analysis_runs run
     WHERE run.id = NEW.analysis_run_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Analysis Run is unavailable';
    END IF;

    IF TG_OP = 'INSERT' THEN
        PERFORM 1 FROM source_documents WHERE id = source_document_id FOR UPDATE;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'Source Document has been deleted';
        END IF;
    ELSIF NOT EXISTS (SELECT 1 FROM source_documents WHERE id = source_document_id) THEN
        RAISE EXCEPTION 'Source Document has been deleted';
    END IF;
    IF EXISTS (
        SELECT 1 FROM source_document_tombstones WHERE document_id = source_document_id
    ) THEN
        RAISE EXCEPTION 'Source Document has been deleted';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

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
        EXECUTE format(
            'CREATE TRIGGER %I BEFORE INSERT OR UPDATE ON %I FOR EACH ROW EXECUTE FUNCTION reject_deleted_document_analysis_write()',
            table_name || '_require_active_source_document',
            table_name
        );
    END LOOP;
END;
$$;

ALTER TABLE cited_paper_indexing
    DROP CONSTRAINT cited_paper_indexing_parse_fk,
    ADD CONSTRAINT cited_paper_indexing_parse_fk
        FOREIGN KEY (cited_paper_parse_id, analysis_run_id, bibliography_entry_id)
        REFERENCES cited_paper_parses(id, analysis_run_id, bibliography_entry_id) ON DELETE CASCADE;
