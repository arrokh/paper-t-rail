DO $$
BEGIN
    IF to_regclass('public.recovery_batches') IS NULL
       OR to_regclass('public.recovery_batch_uploads') IS NULL
       OR to_regclass('public.recovery_upload_cleanup_tombstones') IS NULL
       OR to_regclass('public.recovery_batches_one_open_per_run_idx') IS NULL
       OR to_regclass('public.recovery_batch_uploads_one_active_entry_idx') IS NULL
       OR to_regclass('public.recovery_upload_cleanup_due_idx') IS NULL
       OR NOT EXISTS (
           SELECT 1 FROM pg_trigger
            WHERE tgname = 'recovery_batches_provenance_and_status_guard'
              AND NOT tgisinternal
       )
       OR NOT EXISTS (
           SELECT 1 FROM pg_trigger
            WHERE tgname = 'recovery_batch_uploads_provenance_and_status_guard'
              AND NOT tgisinternal
       )
       OR NOT EXISTS (
           SELECT 1 FROM pg_trigger
            WHERE tgname = 'recovery_batches_require_active_source_document'
              AND NOT tgisinternal
       )
       OR NOT EXISTS (
           SELECT 1 FROM pg_trigger
            WHERE tgname = 'recovery_batch_uploads_require_active_source_document'
              AND NOT tgisinternal
       ) THEN
        RAISE EXCEPTION 'Recovery PDF staging tables, indexes, or deletion guards are missing';
    END IF;
END;
$$;
