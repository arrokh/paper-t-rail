ALTER TABLE recovery_batches
    ADD COLUMN revision BIGINT NOT NULL DEFAULT 0 CHECK (revision >= 0);

CREATE FUNCTION enforce_recovery_batch_revision_monotonic() RETURNS trigger AS $$
BEGIN
    IF NEW.revision < OLD.revision THEN
        RAISE EXCEPTION 'Recovery Batch revision cannot decrease';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER recovery_batches_revision_is_monotonic
    BEFORE UPDATE ON recovery_batches
    FOR EACH ROW EXECUTE FUNCTION enforce_recovery_batch_revision_monotonic();
