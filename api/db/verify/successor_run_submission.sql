DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
         WHERE conrelid = 'recovery_batches'::regclass
           AND conname = 'recovery_batches_status_check'
           AND contype = 'c'
           AND convalidated
           AND pg_get_constraintdef(oid) LIKE '%SUBMITTED%'
    ) OR NOT EXISTS (
        SELECT 1 FROM pg_constraint
         WHERE conrelid = 'recovery_batches'::regclass
           AND conname = 'recovery_batches_status_expiry_shape_check'
           AND contype = 'c'
           AND convalidated
    ) OR NOT EXISTS (
        SELECT 1 FROM pg_trigger
         WHERE tgrelid = 'recovery_batch_uploads'::regclass
           AND tgname = 'submitted_recovery_uploads_are_immutable'
           AND tgenabled <> 'D'
    ) THEN
        RAISE EXCEPTION 'Submitted Recovery Batch state or upload immutability guard is missing';
    END IF;

    IF (
        SELECT count(*) FROM pg_constraint
         WHERE conrelid = 'recovery_batch_successor_submissions'::regclass
           AND contype = 'u'
           AND convalidated
           AND pg_get_constraintdef(oid) IN (
               'UNIQUE (batch_id)',
               'UNIQUE (successor_analysis_run_id)',
               'UNIQUE (batch_id, idempotency_key)'
           )
    ) <> 3 THEN
        RAISE EXCEPTION 'Successor submission uniqueness or idempotency constraints are missing';
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
         WHERE table_schema = 'public'
           AND table_name = 'analysis_run_successor_work_manifest'
           AND column_name = 'stage_dispositions'
           AND data_type = 'jsonb'
    ) OR NOT EXISTS (
        SELECT 1 FROM pg_constraint
         WHERE conrelid = 'analysis_run_successor_asset_ownership'::regclass
           AND contype = 'u'
           AND convalidated
           AND pg_get_constraintdef(oid) = 'UNIQUE (upload_id)'
    ) OR NOT EXISTS (
        SELECT 1 FROM pg_constraint constraint_row
         WHERE conrelid = 'analysis_run_successor_asset_ownership'::regclass
           AND contype = 'f'
           AND confrelid = 'cited_paper_access'::regclass
           AND convalidated
           AND pg_get_constraintdef(oid) LIKE 'FOREIGN KEY (asset_id, successor_analysis_run_id, bibliography_entry_id)%'
    ) OR NOT EXISTS (
        SELECT 1 FROM pg_constraint
         WHERE conrelid = 'analysis_run_successor_artifact_lineage'::regclass
           AND contype = 'p'
           AND convalidated
    ) THEN
        RAISE EXCEPTION 'Successor manifest, ownership, or typed lineage constraints are missing';
    END IF;
END;
$$;
