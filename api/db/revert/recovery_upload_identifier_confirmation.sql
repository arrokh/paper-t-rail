DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM recovery_upload_identifier_confirmations) THEN
        RAISE EXCEPTION 'Cannot revert DOI candidate confirmations while persisted confirmations exist';
    END IF;
END;
$$;

DROP TRIGGER IF EXISTS recovery_upload_identifier_confirmations_require_active_source ON recovery_upload_identifier_confirmations;
DROP TRIGGER IF EXISTS recovery_upload_identifier_confirmations_are_immutable ON recovery_upload_identifier_confirmations;
DROP TRIGGER IF EXISTS recovery_upload_identifier_confirmations_validate ON recovery_upload_identifier_confirmations;
DROP TABLE IF EXISTS recovery_upload_identifier_confirmations;
DROP FUNCTION IF EXISTS prevent_recovery_upload_identifier_confirmation_mutation();
DROP FUNCTION IF EXISTS enforce_recovery_upload_identifier_confirmation();

ALTER TABLE recovery_upload_identity_confirmations
    DROP CONSTRAINT IF EXISTS recovery_upload_identity_confirmations_attempt_version_key;
ALTER TABLE recovery_upload_validation_attempts
    DROP CONSTRAINT IF EXISTS recovery_upload_validation_attempts_batch_upload_version_key;
