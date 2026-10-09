CREATE FUNCTION enforce_recovery_upload_validation_attempt_state() RETURNS trigger AS $$
DECLARE
    upload_batch_id UUID;
    upload_analysis_run_id UUID;
    upload_status VARCHAR(24);
    batch_status VARCHAR(16);
    batch_expiry TIMESTAMPTZ;
BEGIN
    SELECT upload.batch_id, upload.analysis_run_id, upload.status, batch.status, batch.expires_at
      INTO upload_batch_id, upload_analysis_run_id, upload_status, batch_status, batch_expiry
      FROM recovery_batch_uploads upload
      JOIN recovery_batches batch ON batch.id = upload.batch_id
     WHERE upload.id = NEW.upload_id
     FOR UPDATE OF batch, upload;

    IF NOT FOUND
       OR NEW.batch_id IS DISTINCT FROM upload_batch_id
       OR NEW.analysis_run_id IS DISTINCT FROM upload_analysis_run_id
       OR upload_status <> 'STAGED'
       OR batch_status <> 'OPEN'
       OR batch_expiry <= now() THEN
        RAISE EXCEPTION 'Recovery Upload validation requires an active finalized upload';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER recovery_upload_validation_attempts_validate
    BEFORE INSERT ON recovery_upload_validation_attempts
    FOR EACH ROW EXECUTE FUNCTION enforce_recovery_upload_validation_attempt_state();

CREATE TRIGGER recovery_upload_validation_active_document_guard
    BEFORE INSERT OR UPDATE ON recovery_upload_validation_attempts
    FOR EACH ROW EXECUTE FUNCTION reject_deleted_document_analysis_write();
