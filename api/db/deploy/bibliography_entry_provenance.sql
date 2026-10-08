ALTER TABLE parsed_document_parses
    ADD COLUMN bibliography_normalization_policy_id VARCHAR(80),
    ADD COLUMN bibliography_normalization_policy_version VARCHAR(80),
    ADD CONSTRAINT parsed_document_parses_bibliography_policy_pair_check
        CHECK ((bibliography_normalization_policy_id IS NULL) = (bibliography_normalization_policy_version IS NULL));

ALTER TABLE bibliography_entries
    ALTER COLUMN local_reference_key TYPE TEXT,
    ADD COLUMN source_element VARCHAR(24),
    ADD COLUMN source_text_content TEXT,
    ADD COLUMN source_local_reference_key TEXT,
    ADD COLUMN local_reference_key_origin VARCHAR(32),
    ADD COLUMN identifiers JSONB,
    ADD COLUMN source_locations JSONB,
    ADD COLUMN provisional_artifact_signals JSONB,
    ADD COLUMN extraction_limitations JSONB,
    ADD COLUMN provenance_capture_status VARCHAR(32),
    ADD CONSTRAINT bibliography_entries_local_key_origin_check
        CHECK (local_reference_key_origin IS NULL OR local_reference_key_origin IN ('GROBID_XML_ID', 'GENERATED_FALLBACK')),
    ADD CONSTRAINT bibliography_entries_provenance_capture_status_check
        CHECK (provenance_capture_status IS NULL OR provenance_capture_status = 'CAPTURED'),
    ADD CONSTRAINT bibliography_entries_identifiers_array_check
        CHECK (identifiers IS NULL OR jsonb_typeof(identifiers) = 'array'),
    ADD CONSTRAINT bibliography_entries_source_locations_array_check
        CHECK (source_locations IS NULL OR jsonb_typeof(source_locations) = 'array'),
    ADD CONSTRAINT bibliography_entries_artifact_signals_array_check
        CHECK (provisional_artifact_signals IS NULL OR jsonb_typeof(provisional_artifact_signals) = 'array'),
    ADD CONSTRAINT bibliography_entries_extraction_limitations_array_check
        CHECK (extraction_limitations IS NULL OR jsonb_typeof(extraction_limitations) = 'array');

ALTER TABLE citation_occurrences
    ADD COLUMN unmatched_bibliography_reference_keys JSONB,
    ADD CONSTRAINT citation_occurrences_unmatched_reference_keys_array_check
        CHECK (unmatched_bibliography_reference_keys IS NULL OR jsonb_typeof(unmatched_bibliography_reference_keys) = 'array');
