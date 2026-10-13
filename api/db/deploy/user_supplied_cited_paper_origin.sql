ALTER TABLE cited_paper_access
    ALTER COLUMN provider_id DROP NOT NULL;

ALTER TABLE cited_paper_access
    DROP CONSTRAINT cited_paper_access_check;

ALTER TABLE cited_paper_access
    ADD COLUMN access_origin VARCHAR(24) NOT NULL DEFAULT 'AUTOMATIC',
    ADD COLUMN rights_declaration_version VARCHAR(64),
    ADD COLUMN rights_declaration_text TEXT,
    ADD COLUMN rights_declared_at TIMESTAMPTZ,
    ADD COLUMN originating_analysis_run_id UUID,
    ADD COLUMN originating_recovery_batch_id UUID,
    ADD COLUMN originating_recovery_upload_id UUID;

ALTER TABLE cited_paper_access
    ADD CONSTRAINT cited_paper_access_origin_check CHECK (
        (access_origin = 'AUTOMATIC'
            AND provider_id IS NOT NULL
            AND rights_declaration_version IS NULL
            AND rights_declaration_text IS NULL
            AND rights_declared_at IS NULL
            AND originating_analysis_run_id IS NULL
            AND originating_recovery_batch_id IS NULL
            AND originating_recovery_upload_id IS NULL
            AND ((access_status = 'FULL_TEXT_AVAILABLE'
                    AND object_key IS NOT NULL
                    AND content_sha256 IS NOT NULL
                    AND source_url IS NOT NULL
                    AND license_identifier IS NOT NULL
                    AND language_detector_version IS NOT NULL)
                OR (access_status <> 'FULL_TEXT_AVAILABLE'
                    AND object_key IS NULL
                    AND content_sha256 IS NULL
                    AND language IS NULL
                    AND language_detector_version IS NULL)))
        OR
        (access_origin = 'USER_SUPPLIED'
            AND access_status = 'FULL_TEXT_AVAILABLE'
            AND provider_id IS NULL
            AND source_url IS NULL
            AND license_identifier IS NULL
            AND location_version IS NULL
            AND location_host_type IS NULL
            AND content_media_type IS NOT NULL
            AND content_media_type = 'application/pdf'
            AND object_key IS NOT NULL
            AND btrim(object_key) <> ''
            AND content_sha256 IS NOT NULL
            AND language IS NOT NULL AND btrim(language) <> ''
            AND language_detector_version IS NOT NULL AND btrim(language_detector_version) <> ''
            AND rights_declaration_version IS NOT NULL AND btrim(rights_declaration_version) <> ''
            AND rights_declaration_text IS NOT NULL AND btrim(rights_declaration_text) <> ''
            AND rights_declared_at IS NOT NULL
            AND originating_analysis_run_id IS NOT NULL
            AND originating_recovery_batch_id IS NOT NULL
            AND originating_recovery_upload_id IS NOT NULL)
    );

ALTER TABLE cited_paper_access
    ADD CONSTRAINT cited_paper_access_provider_origin_check CHECK (
        (access_origin = 'AUTOMATIC' AND provider_id IS NOT NULL)
        OR (access_origin = 'USER_SUPPLIED' AND provider_id IS NULL)
    );
