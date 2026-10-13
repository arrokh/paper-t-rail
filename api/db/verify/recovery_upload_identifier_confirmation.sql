DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
         WHERE table_schema = 'public'
           AND table_name = 'recovery_upload_identifier_confirmations'
           AND column_name = 'candidate_fingerprint'
           AND data_type = 'character varying'
    ) OR NOT EXISTS (
        SELECT 1 FROM pg_constraint
         WHERE conrelid = 'recovery_upload_identifier_confirmations'::regclass
           AND contype = 'p'
           AND convalidated
    ) OR NOT EXISTS (
        SELECT 1 FROM pg_constraint
         WHERE conrelid = 'recovery_upload_identifier_confirmations'::regclass
           AND contype = 'f'
           AND confrelid = 'recovery_upload_validation_attempts'::regclass
           AND convalidated
    ) OR NOT EXISTS (
        SELECT 1 FROM pg_constraint
         WHERE conrelid = 'recovery_upload_identifier_confirmations'::regclass
           AND contype = 'f'
           AND confrelid = 'recovery_upload_identity_confirmations'::regclass
           AND convalidated
    ) OR NOT EXISTS (
        SELECT 1 FROM pg_trigger
         WHERE tgrelid = 'recovery_upload_identifier_confirmations'::regclass
           AND tgname = 'recovery_upload_identifier_confirmations_validate'
           AND tgenabled <> 'D'
    ) OR NOT EXISTS (
        SELECT 1 FROM pg_trigger
         WHERE tgrelid = 'recovery_upload_identifier_confirmations'::regclass
           AND tgname = 'recovery_upload_identifier_confirmations_are_immutable'
           AND tgenabled <> 'D'
    ) OR NOT EXISTS (
        SELECT 1 FROM pg_trigger
         WHERE tgrelid = 'recovery_upload_identifier_confirmations'::regclass
           AND tgname = 'recovery_upload_identifier_confirmations_require_active_source'
           AND tgenabled <> 'D'
    ) OR NOT EXISTS (
        SELECT 1 FROM pg_constraint
         WHERE conrelid = 'recovery_upload_validation_attempts'::regclass
           AND conname = 'recovery_upload_validation_attempts_batch_upload_version_key'
           AND contype = 'u'
           AND convalidated
    ) OR NOT EXISTS (
        SELECT 1 FROM pg_constraint
         WHERE conrelid = 'recovery_upload_identity_confirmations'::regclass
           AND conname = 'recovery_upload_identity_confirmations_attempt_version_key'
           AND contype = 'u'
           AND convalidated
    ) THEN
        RAISE EXCEPTION 'Recovery Upload DOI candidate confirmation constraints are missing';
    END IF;
END;
$$;
