DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
          FROM information_schema.columns
         WHERE table_schema = 'public'
           AND table_name = 'recovery_upload_validation_attempts'
           AND column_name = 'minimum_extracted_characters'
           AND data_type = 'integer'
    ) OR NOT EXISTS (
        SELECT 1
          FROM information_schema.columns
         WHERE table_schema = 'public'
           AND table_name = 'recovery_upload_validation_attempts'
           AND column_name = 'language_policy_fingerprint'
           AND character_maximum_length = 64
    ) OR NOT EXISTS (
        SELECT 1
          FROM pg_constraint
         WHERE conrelid = 'recovery_upload_validation_attempts'::regclass
           AND conname = 'recovery_upload_validation_language_policy_pair_check'
           AND convalidated
    ) THEN
        RAISE EXCEPTION 'Recovery Upload language-policy evidence columns or constraint are missing';
    END IF;

    IF EXISTS (
        SELECT 1
          FROM recovery_upload_validation_attempts
         WHERE NOT (
             (minimum_extracted_characters IS NULL AND language_policy_fingerprint IS NULL)
             OR (
                 minimum_extracted_characters IS NOT NULL
                 AND minimum_extracted_characters >= 0
                 AND language_policy_fingerprint IS NOT NULL
                 AND language_policy_fingerprint ~ '^[0-9a-f]{64}$'
             )
         )
    ) THEN
        RAISE EXCEPTION 'Recovery Upload validation attempts contain incomplete language-policy evidence';
    END IF;
END;
$$;
