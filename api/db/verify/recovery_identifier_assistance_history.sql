DO $$
BEGIN
    IF to_regclass('public.recovery_batch_uploads_one_active_entry_idx') IS NOT NULL
       OR to_regclass('public.recovery_history_events_run_cursor_idx') IS NULL
       OR NOT EXISTS (
           SELECT 1 FROM information_schema.columns
            WHERE table_schema = 'public' AND table_name = 'recovery_history_events'
              AND column_name = 'append_sequence' AND is_identity = 'YES' AND is_nullable = 'NO'
       ) OR NOT EXISTS (
           SELECT 1 FROM pg_indexes
            WHERE schemaname = 'public' AND indexname = 'recovery_history_events_run_cursor_idx'
              AND indexdef LIKE '%(analysis_run_id, append_sequence DESC)%'
       ) OR to_regclass('public.recovery_batch_identifier_assistance') IS NULL
       OR to_regclass('public.analysis_run_successor_doi_assistance') IS NULL
       OR NOT EXISTS (
           SELECT 1 FROM information_schema.columns
            WHERE table_schema = 'public' AND table_name = 'recovery_batches'
              AND column_name = 'rights_declaration_version' AND is_nullable = 'YES'
       ) OR NOT EXISTS (
           SELECT 1 FROM information_schema.columns
            WHERE table_schema = 'public' AND table_name = 'recovery_batches'
              AND column_name = 'creation_rights_declaration_version'
       ) OR NOT EXISTS (
           SELECT 1 FROM pg_constraint
            WHERE conrelid = 'recovery_batches'::regclass
              AND conname = 'recovery_batches_rights_snapshot_shape_check'
              AND contype = 'c' AND convalidated
       ) OR NOT EXISTS (
           SELECT 1 FROM pg_proc
            WHERE proname = 'enforce_recovery_batch_provenance_and_status'
              AND pg_get_functiondef(oid) LIKE '%OLD.status <> ''OPEN'' OR NEW.status <> ''OPEN''%'
       ) OR NOT EXISTS (
           SELECT 1 FROM pg_constraint
            WHERE conrelid = 'analysis_run_successor_work_manifest'::regclass
              AND conname = 'analysis_run_successor_work_manifest_selection_shape_check'
              AND contype = 'c' AND convalidated
       ) OR NOT EXISTS (
           SELECT 1 FROM pg_constraint
            WHERE conrelid = 'recovery_history_events'::regclass
              AND contype = 'c' AND convalidated
              AND pg_get_constraintdef(oid) LIKE '%EXACT_VERSION_CONFIRMED%'
              AND pg_get_constraintdef(oid) LIKE '%attempt_number%'
       ) OR NOT EXISTS (
           SELECT 1 FROM pg_trigger
            WHERE tgrelid = 'recovery_history_events'::regclass
              AND tgname = 'recovery_history_events_are_append_only' AND tgenabled <> 'D'
       ) OR NOT EXISTS (
           SELECT 1 FROM pg_trigger
            WHERE tgrelid = 'analysis_run_successor_doi_assistance'::regclass
              AND tgname = 'successor_doi_assistance_is_immutable' AND tgenabled <> 'D'
       ) OR NOT EXISTS (
           SELECT 1 FROM pg_trigger
            WHERE tgrelid = 'analysis_run_successor_doi_assistance'::regclass
              AND tgname = 'successor_doi_assistance_requires_active_source_document' AND tgenabled <> 'D'
       ) THEN
        RAISE EXCEPTION 'Recovery identifier-assistance and history schema is incomplete';
    END IF;
END;
$$;
