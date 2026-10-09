DO $$
BEGIN
    IF to_regclass('public.recovery_upload_validation_attempts') IS NULL
       OR to_regclass('public.recovery_upload_validation_attempts_latest_idx') IS NULL
       OR NOT EXISTS (
           SELECT 1 FROM pg_trigger
            WHERE tgname = 'recovery_upload_validation_attempts_are_immutable'
              AND NOT tgisinternal
       ) THEN
        RAISE EXCEPTION 'Recovery Upload validation attempts table, index, or immutability guard is missing';
    END IF;
END;
$$;
