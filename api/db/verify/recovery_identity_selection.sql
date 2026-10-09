DO $$
BEGIN
    IF to_regclass('public.recovery_upload_identity_confirmations') IS NULL
       OR to_regclass('public.recovery_upload_identity_confirmations_upload_idx') IS NULL
       OR to_regclass('public.recovery_upload_asset_selections') IS NULL
       OR NOT EXISTS (
           SELECT 1 FROM pg_trigger
            WHERE tgname = 'recovery_upload_identity_confirmations_validate'
              AND NOT tgisinternal
       )
       OR NOT EXISTS (
           SELECT 1 FROM pg_trigger
            WHERE tgname = 'recovery_upload_asset_selections_validate'
              AND NOT tgisinternal
       )
       OR NOT EXISTS (
           SELECT 1 FROM pg_trigger
            WHERE tgname = 'recovery_upload_identity_confirmations_require_active_source_document'
              AND NOT tgisinternal
       )
       OR NOT EXISTS (
           SELECT 1 FROM pg_trigger
            WHERE tgname = 'recovery_upload_asset_selections_require_active_source_document'
              AND NOT tgisinternal
       )
       OR NOT EXISTS (
           SELECT 1 FROM pg_trigger
            WHERE tgname = 'recovery_validation_attempts_clear_stale_selection'
              AND NOT tgisinternal
       ) THEN
        RAISE EXCEPTION 'Recovery Upload confirmation or exact-version selection guards are missing';
    END IF;
END;
$$;
