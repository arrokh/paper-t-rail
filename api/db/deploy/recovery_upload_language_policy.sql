ALTER TABLE recovery_upload_validation_attempts
    ADD COLUMN minimum_extracted_characters INTEGER,
    ADD COLUMN language_policy_fingerprint VARCHAR(64);

ALTER TABLE recovery_upload_validation_attempts
    ADD CONSTRAINT recovery_upload_validation_language_policy_pair_check
    CHECK (
        (minimum_extracted_characters IS NULL AND language_policy_fingerprint IS NULL)
        OR (
            minimum_extracted_characters IS NOT NULL
            AND minimum_extracted_characters >= 0
            AND language_policy_fingerprint IS NOT NULL
            AND language_policy_fingerprint ~ '^[0-9a-f]{64}$'
        )
    );
