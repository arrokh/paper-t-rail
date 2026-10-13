DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
         WHERE table_schema = 'public'
           AND table_name = 'analysis_run_successor_resolution_assistance'
           AND column_name = 'analysis_run_id'
           AND data_type = 'uuid'
    ) OR NOT EXISTS (
        SELECT 1 FROM information_schema.columns
         WHERE table_schema = 'public'
           AND table_name = 'analysis_run_successor_resolution_assistance'
           AND column_name = 'identifier_value'
           AND data_type = 'text'
    ) OR NOT EXISTS (
        SELECT 1 FROM information_schema.columns
         WHERE table_schema = 'public'
           AND table_name = 'analysis_run_successor_resolution_assistance'
           AND column_name = 'candidate_fingerprint'
           AND data_type = 'character varying'
    ) OR NOT EXISTS (
        SELECT 1 FROM pg_constraint
         WHERE conrelid = 'analysis_run_successor_resolution_assistance'::regclass
           AND contype = 'p'
           AND convalidated
    ) OR NOT EXISTS (
        SELECT 1 FROM pg_constraint
         WHERE conrelid = 'analysis_run_successor_resolution_assistance'::regclass
           AND contype = 'f'
           AND confrelid = 'recovery_upload_asset_selections'::regclass
           AND convalidated
           AND pg_get_constraintdef(oid) LIKE 'FOREIGN KEY (batch_id, predecessor_bibliography_entry_id, upload_id, validation_attempt_id, content_sha256)%'
    ) OR NOT EXISTS (
        SELECT 1 FROM pg_constraint
         WHERE conrelid = 'analysis_run_successor_resolution_assistance'::regclass
           AND contype = 'f'
           AND confrelid = 'recovery_upload_identifier_confirmations'::regclass
           AND convalidated
           AND pg_get_constraintdef(oid) LIKE 'FOREIGN KEY (validation_attempt_id, doi_confirmation_id, content_sha256, identifier_type, identifier_value, candidate_fingerprint)%'
    ) OR NOT EXISTS (
        SELECT 1 FROM pg_trigger
         WHERE tgrelid = 'analysis_run_successor_resolution_assistance'::regclass
           AND tgname = 'successor_resolution_assistance_is_immutable'
           AND tgenabled <> 'D'
    ) OR NOT EXISTS (
        SELECT 1 FROM pg_trigger
         WHERE tgrelid = 'analysis_run_successor_resolution_assistance'::regclass
           AND tgname = 'successor_resolution_assistance_requires_active_source_document'
           AND tgenabled <> 'D'
    ) THEN
        RAISE EXCEPTION 'Successor Resolution Assistance provenance or immutability constraints are missing';
    END IF;
END;
$$;
