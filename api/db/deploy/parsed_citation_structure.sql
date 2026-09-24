ALTER TABLE analysis_runs
    DROP CONSTRAINT analysis_runs_status_check,
    ADD CONSTRAINT analysis_runs_status_check CHECK (status IN (
        'QUEUED', 'PROCESSING', 'PARSED', 'COMPLETED', 'COMPLETED_WITH_WARNINGS', 'FAILED'
    )),
    ADD CONSTRAINT analysis_runs_parse_provenance_unique UNIQUE (id, source_content_sha256);

CREATE OR REPLACE FUNCTION enforce_analysis_run_provenance_and_transitions() RETURNS trigger AS $$
BEGIN
    IF NEW.id IS DISTINCT FROM OLD.id
       OR NEW.document_id IS DISTINCT FROM OLD.document_id
       OR NEW.source_content_sha256 IS DISTINCT FROM OLD.source_content_sha256
       OR NEW.source_parser_id IS DISTINCT FROM OLD.source_parser_id
       OR NEW.source_parser_version IS DISTINCT FROM OLD.source_parser_version
       OR NEW.configuration_snapshot IS DISTINCT FROM OLD.configuration_snapshot
       OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'Analysis Run provenance is immutable';
    END IF;

    IF NEW.status IS DISTINCT FROM OLD.status AND NOT (
        (OLD.status = 'QUEUED' AND NEW.status IN ('PROCESSING', 'FAILED'))
        OR (OLD.status = 'PROCESSING' AND NEW.status IN ('PARSED', 'COMPLETED', 'COMPLETED_WITH_WARNINGS', 'FAILED'))
        OR (OLD.status = 'PARSED' AND NEW.status IN ('PROCESSING', 'COMPLETED', 'COMPLETED_WITH_WARNINGS', 'FAILED'))
    ) THEN
        RAISE EXCEPTION 'Invalid Analysis Run status transition: % -> %', OLD.status, NEW.status;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TABLE parsed_document_parses (
    analysis_run_id UUID PRIMARY KEY REFERENCES analysis_runs(id) ON DELETE CASCADE,
    source_content_sha256 VARCHAR(64) NOT NULL CHECK (source_content_sha256 ~ '^[0-9a-f]{64}$'),
    parser_id VARCHAR(80) NOT NULL,
    parser_version VARCHAR(80) NOT NULL,
    normalized_source_text TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY (analysis_run_id, source_content_sha256)
        REFERENCES analysis_runs(id, source_content_sha256) ON DELETE CASCADE
);

CREATE TABLE parsed_document_sections (
    id UUID PRIMARY KEY,
    analysis_run_id UUID NOT NULL REFERENCES parsed_document_parses(analysis_run_id) ON DELETE CASCADE,
    section_order INTEGER NOT NULL CHECK (section_order >= 0),
    heading TEXT,
    text TEXT NOT NULL,
    start_offset INTEGER NOT NULL CHECK (start_offset >= 0),
    end_offset INTEGER NOT NULL CHECK (end_offset >= start_offset),
    source_metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    UNIQUE (analysis_run_id, id),
    UNIQUE (analysis_run_id, section_order)
);

CREATE TABLE citation_contexts (
    id UUID PRIMARY KEY,
    analysis_run_id UUID NOT NULL REFERENCES parsed_document_parses(analysis_run_id) ON DELETE CASCADE,
    section_id UUID NOT NULL,
    context_text TEXT NOT NULL,
    boundary_kind VARCHAR(32) NOT NULL CHECK (boundary_kind IN ('CLAUSE', 'SENTENCE_FALLBACK')),
    start_offset INTEGER NOT NULL CHECK (start_offset >= 0),
    end_offset INTEGER NOT NULL CHECK (end_offset > start_offset),
    UNIQUE (analysis_run_id, id),
    UNIQUE (analysis_run_id, start_offset, end_offset),
    FOREIGN KEY (analysis_run_id, section_id)
        REFERENCES parsed_document_sections(analysis_run_id, id) ON DELETE CASCADE
);

CREATE TABLE citation_occurrences (
    id UUID PRIMARY KEY,
    analysis_run_id UUID NOT NULL,
    citation_context_id UUID NOT NULL,
    section_id UUID NOT NULL,
    marker_text TEXT NOT NULL,
    start_offset INTEGER NOT NULL CHECK (start_offset >= 0),
    end_offset INTEGER NOT NULL CHECK (end_offset > start_offset),
    FOREIGN KEY (analysis_run_id, citation_context_id)
        REFERENCES citation_contexts(analysis_run_id, id) ON DELETE CASCADE,
    FOREIGN KEY (analysis_run_id, section_id)
        REFERENCES parsed_document_sections(analysis_run_id, id) ON DELETE CASCADE,
    UNIQUE (analysis_run_id, id)
);

CREATE TABLE bibliography_entries (
    id UUID PRIMARY KEY,
    analysis_run_id UUID NOT NULL REFERENCES parsed_document_parses(analysis_run_id) ON DELETE CASCADE,
    entry_order INTEGER NOT NULL CHECK (entry_order >= 0),
    local_reference_key VARCHAR(255) NOT NULL,
    raw_text TEXT NOT NULL,
    parsed_title TEXT,
    parsed_authors JSONB NOT NULL DEFAULT '[]'::jsonb,
    parsed_year INTEGER,
    parsed_doi TEXT,
    reference_type VARCHAR(64) NOT NULL,
    resolution_status VARCHAR(32) NOT NULL DEFAULT 'NOT_ATTEMPTED',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (analysis_run_id, id),
    UNIQUE (analysis_run_id, local_reference_key)
);

CREATE TABLE citation_targets (
    id UUID PRIMARY KEY,
    analysis_run_id UUID NOT NULL,
    citation_occurrence_id UUID NOT NULL,
    bibliography_entry_id UUID NOT NULL,
    target_order INTEGER NOT NULL CHECK (target_order >= 0),
    FOREIGN KEY (analysis_run_id, citation_occurrence_id)
        REFERENCES citation_occurrences(analysis_run_id, id) ON DELETE CASCADE,
    FOREIGN KEY (analysis_run_id, bibliography_entry_id)
        REFERENCES bibliography_entries(analysis_run_id, id) ON DELETE CASCADE,
    UNIQUE (analysis_run_id, citation_occurrence_id, bibliography_entry_id),
    UNIQUE (analysis_run_id, citation_occurrence_id, target_order)
);

CREATE INDEX citation_contexts_run_span_idx
    ON citation_contexts(analysis_run_id, start_offset);
CREATE INDEX citation_occurrences_run_span_idx
    ON citation_occurrences(analysis_run_id, start_offset);
CREATE INDEX bibliography_entries_run_order_idx
    ON bibliography_entries(analysis_run_id, entry_order);

CREATE FUNCTION prevent_parsed_structure_update() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'Parsed document structure is immutable';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER parsed_document_parses_are_immutable
    BEFORE UPDATE ON parsed_document_parses
    FOR EACH ROW EXECUTE FUNCTION prevent_parsed_structure_update();
CREATE TRIGGER parsed_document_sections_are_immutable
    BEFORE UPDATE ON parsed_document_sections
    FOR EACH ROW EXECUTE FUNCTION prevent_parsed_structure_update();
CREATE TRIGGER citation_contexts_are_immutable
    BEFORE UPDATE ON citation_contexts
    FOR EACH ROW EXECUTE FUNCTION prevent_parsed_structure_update();
CREATE TRIGGER citation_occurrences_are_immutable
    BEFORE UPDATE ON citation_occurrences
    FOR EACH ROW EXECUTE FUNCTION prevent_parsed_structure_update();
CREATE TRIGGER bibliography_entries_are_immutable
    BEFORE UPDATE ON bibliography_entries
    FOR EACH ROW EXECUTE FUNCTION prevent_parsed_structure_update();
CREATE TRIGGER citation_targets_are_immutable
    BEFORE UPDATE ON citation_targets
    FOR EACH ROW EXECUTE FUNCTION prevent_parsed_structure_update();
