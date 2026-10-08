DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM bibliography_entries WHERE length(local_reference_key) > 255) THEN
        RAISE EXCEPTION 'Cannot revert bibliography local keys to VARCHAR(255) while longer keys exist';
    END IF;
END;
$$;

ALTER TABLE citation_occurrences
    DROP CONSTRAINT citation_occurrences_unmatched_reference_keys_array_check,
    DROP COLUMN unmatched_bibliography_reference_keys;

ALTER TABLE bibliography_entries
    DROP CONSTRAINT bibliography_entries_extraction_limitations_array_check,
    DROP CONSTRAINT bibliography_entries_artifact_signals_array_check,
    DROP CONSTRAINT bibliography_entries_source_locations_array_check,
    DROP CONSTRAINT bibliography_entries_identifiers_array_check,
    DROP CONSTRAINT bibliography_entries_provenance_capture_status_check,
    DROP CONSTRAINT bibliography_entries_local_key_origin_check,
    DROP COLUMN provenance_capture_status,
    DROP COLUMN extraction_limitations,
    DROP COLUMN provisional_artifact_signals,
    DROP COLUMN source_locations,
    DROP COLUMN identifiers,
    DROP COLUMN local_reference_key_origin,
    DROP COLUMN source_local_reference_key,
    DROP COLUMN source_text_content,
    DROP COLUMN source_element,
    ALTER COLUMN local_reference_key TYPE VARCHAR(255);

ALTER TABLE parsed_document_parses
    DROP CONSTRAINT parsed_document_parses_bibliography_policy_pair_check,
    DROP COLUMN bibliography_normalization_policy_version,
    DROP COLUMN bibliography_normalization_policy_id;
