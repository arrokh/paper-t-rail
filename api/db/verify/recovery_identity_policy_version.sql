DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
          FROM information_schema.columns
         WHERE table_schema = current_schema()
           AND table_name = 'recovery_upload_validation_attempts'
           AND column_name = 'identity_policy_version'
           AND is_nullable = 'NO'
           AND column_default LIKE '%recovery-upload-identity-v1%'
    ) THEN
        RAISE EXCEPTION 'Recovery Upload identity policy version is missing or has no legacy default';
    END IF;
END;
$$;
