UPDATE analysis_runs
   SET status = 'FAILED',
       failure_reason = 'Parsed document structure was removed when its database migration was reverted.',
       progress = '{"stage":"FAILED","percent":0,"message":"Parsed document storage was reverted."}'::jsonb,
       completed_at = now(),
       updated_at = now()
 WHERE status = 'PARSED';

DROP TRIGGER citation_targets_are_immutable ON citation_targets;
DROP TRIGGER bibliography_entries_are_immutable ON bibliography_entries;
DROP TRIGGER citation_occurrences_are_immutable ON citation_occurrences;
DROP TRIGGER citation_contexts_are_immutable ON citation_contexts;
DROP TRIGGER parsed_document_sections_are_immutable ON parsed_document_sections;
DROP TRIGGER parsed_document_parses_are_immutable ON parsed_document_parses;
DROP FUNCTION prevent_parsed_structure_update();

DROP TABLE citation_targets;
DROP TABLE bibliography_entries;
DROP TABLE citation_occurrences;
DROP TABLE citation_contexts;
DROP TABLE parsed_document_sections;
DROP TABLE parsed_document_parses;

ALTER TABLE analysis_runs
    DROP CONSTRAINT analysis_runs_parse_provenance_unique,
    DROP CONSTRAINT analysis_runs_status_check,
    ADD CONSTRAINT analysis_runs_status_check CHECK (status IN (
        'QUEUED', 'PROCESSING', 'COMPLETED', 'COMPLETED_WITH_WARNINGS', 'FAILED'
    ));

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
        OR (OLD.status = 'PROCESSING' AND NEW.status IN ('COMPLETED', 'COMPLETED_WITH_WARNINGS', 'FAILED'))
    ) THEN
        RAISE EXCEPTION 'Invalid Analysis Run status transition: % -> %', OLD.status, NEW.status;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
