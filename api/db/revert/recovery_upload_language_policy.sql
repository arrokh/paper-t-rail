ALTER TABLE recovery_upload_validation_attempts
    DROP CONSTRAINT recovery_upload_validation_language_policy_pair_check,
    DROP COLUMN language_policy_fingerprint,
    DROP COLUMN minimum_extracted_characters;
