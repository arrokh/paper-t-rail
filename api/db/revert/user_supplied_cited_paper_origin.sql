ALTER TABLE cited_paper_access
    DROP CONSTRAINT cited_paper_access_provider_origin_check,
    DROP CONSTRAINT cited_paper_access_origin_check,
    ADD CONSTRAINT cited_paper_access_check CHECK (
        (access_status = 'FULL_TEXT_AVAILABLE' AND object_key IS NOT NULL AND content_sha256 IS NOT NULL
            AND source_url IS NOT NULL AND license_identifier IS NOT NULL AND language_detector_version IS NOT NULL)
        OR (access_status <> 'FULL_TEXT_AVAILABLE' AND object_key IS NULL AND content_sha256 IS NULL
            AND language IS NULL AND language_detector_version IS NULL)
    );

ALTER TABLE cited_paper_access
    ALTER COLUMN provider_id SET NOT NULL,
    DROP COLUMN originating_recovery_upload_id,
    DROP COLUMN originating_recovery_batch_id,
    DROP COLUMN originating_analysis_run_id,
    DROP COLUMN rights_declared_at,
    DROP COLUMN rights_declaration_text,
    DROP COLUMN rights_declaration_version,
    DROP COLUMN access_origin;
