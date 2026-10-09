DO $$
BEGIN
    IF NOT EXISTS (
           SELECT 1 FROM pg_trigger
            WHERE tgname = 'recovery_upload_validation_attempts_validate'
              AND NOT tgisinternal
       )
       OR NOT EXISTS (
           SELECT 1 FROM pg_trigger
            WHERE tgname = 'recovery_upload_validation_active_document_guard'
              AND NOT tgisinternal
       ) THEN
        RAISE EXCEPTION 'Recovery Upload validation active-state and source-deletion guards are missing';
    END IF;
END;
$$;
