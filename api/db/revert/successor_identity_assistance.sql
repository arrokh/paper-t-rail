DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM recovery_batches WHERE status = 'SUBMITTED')
       OR EXISTS (SELECT 1 FROM recovery_batch_successor_submissions)
       OR EXISTS (SELECT 1 FROM analysis_run_successor_work_manifest)
       OR EXISTS (SELECT 1 FROM analysis_run_successor_artifact_lineage)
       OR EXISTS (SELECT 1 FROM analysis_run_successor_asset_ownership)
       OR EXISTS (SELECT 1 FROM analysis_run_successor_resolution_assistance) THEN
        RAISE EXCEPTION 'Cannot revert successor identity assistance while persisted successor data exists';
    END IF;
END;
$$;

DROP TRIGGER IF EXISTS successor_resolution_assistance_requires_active_source_document ON analysis_run_successor_resolution_assistance;
DROP TRIGGER IF EXISTS successor_resolution_assistance_is_immutable ON analysis_run_successor_resolution_assistance;
DROP TABLE IF EXISTS analysis_run_successor_resolution_assistance;
DROP FUNCTION IF EXISTS prevent_successor_resolution_assistance_mutation();

ALTER TABLE recovery_upload_asset_selections
    DROP CONSTRAINT IF EXISTS recovery_upload_asset_selections_exact_attempt_version_key;
