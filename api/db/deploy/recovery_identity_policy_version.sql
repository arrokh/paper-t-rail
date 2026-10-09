ALTER TABLE recovery_upload_validation_attempts
    ADD COLUMN identity_policy_version VARCHAR(80) NOT NULL DEFAULT 'recovery-upload-identity-v1';
