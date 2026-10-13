DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
          FROM information_schema.columns
         WHERE table_schema = 'public'
           AND table_name = 'recovery_batches'
           AND column_name = 'revision'
           AND data_type = 'bigint'
           AND column_default = '0'
           AND is_nullable = 'NO'
    ) OR NOT EXISTS (
        SELECT 1
          FROM pg_constraint
         WHERE conrelid = 'recovery_batches'::regclass
           AND conname = 'recovery_batches_revision_check'
           AND convalidated
    ) OR NOT EXISTS (
        SELECT 1
          FROM pg_trigger
         WHERE tgrelid = 'recovery_batches'::regclass
           AND tgname = 'recovery_batches_revision_is_monotonic'
           AND tgenabled <> 'D'
    ) THEN
        RAISE EXCEPTION 'Recovery Batch revision column or monotonic guard is missing';
    END IF;

    IF EXISTS (SELECT 1 FROM recovery_batches WHERE revision < 0) THEN
        RAISE EXCEPTION 'Recovery Batch revisions cannot be negative';
    END IF;
END;
$$;
