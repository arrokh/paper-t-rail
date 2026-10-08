DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
         WHERE table_name = 'parsed_document_parses'
           AND column_name = 'bibliography_normalization_policy_id'
    ) OR NOT EXISTS (
        SELECT 1 FROM information_schema.columns
         WHERE table_name = 'parsed_document_parses'
           AND column_name = 'bibliography_normalization_policy_version'
    ) OR NOT EXISTS (
        SELECT 1 FROM information_schema.columns
         WHERE table_name = 'bibliography_entries'
           AND column_name = 'source_text_content'
    ) OR NOT EXISTS (
        SELECT 1 FROM information_schema.columns
         WHERE table_name = 'bibliography_entries'
           AND column_name = 'identifiers'
    ) OR NOT EXISTS (
        SELECT 1 FROM information_schema.columns
         WHERE table_name = 'bibliography_entries'
           AND column_name = 'provenance_capture_status'
    ) OR NOT EXISTS (
        SELECT 1 FROM information_schema.columns
         WHERE table_name = 'citation_occurrences'
           AND column_name = 'unmatched_bibliography_reference_keys'
    ) THEN
        RAISE EXCEPTION 'Versioned bibliography provenance columns are missing';
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
         WHERE table_name = 'bibliography_entries'
           AND column_name = 'local_reference_key'
           AND data_type = 'text'
    ) OR NOT EXISTS (
        SELECT 1 FROM pg_constraint
         WHERE conname = 'parsed_document_parses_bibliography_policy_pair_check'
    ) OR NOT EXISTS (
        SELECT 1 FROM pg_constraint
         WHERE conname = 'bibliography_entries_local_key_origin_check'
    ) OR NOT EXISTS (
        SELECT 1 FROM pg_constraint
         WHERE conname = 'citation_occurrences_unmatched_reference_keys_array_check'
    ) THEN
        RAISE EXCEPTION 'Versioned bibliography provenance constraints are missing';
    END IF;
END;
$$;
