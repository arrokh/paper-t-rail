DROP TRIGGER IF EXISTS recovery_batches_revision_is_monotonic ON recovery_batches;
DROP FUNCTION IF EXISTS enforce_recovery_batch_revision_monotonic();
ALTER TABLE recovery_batches DROP CONSTRAINT IF EXISTS recovery_batches_revision_check;
ALTER TABLE recovery_batches DROP COLUMN IF EXISTS revision;
