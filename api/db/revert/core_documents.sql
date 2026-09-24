DROP TRIGGER analysis_run_provenance_and_status_are_guarded ON analysis_runs;
DROP FUNCTION enforce_analysis_run_provenance_and_transitions();
DROP TRIGGER source_documents_are_immutable ON source_documents;
DROP FUNCTION enforce_source_document_immutability();
DROP TABLE inbox_events;
DROP TABLE outbox_events;
DROP TABLE analysis_runs;
DROP TABLE source_documents;
