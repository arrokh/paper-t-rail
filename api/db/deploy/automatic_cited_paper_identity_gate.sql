ALTER TABLE cited_paper_access
    DROP CONSTRAINT cited_paper_access_access_reasons_check;

ALTER TABLE cited_paper_access
    ADD CONSTRAINT cited_paper_access_access_reasons_check CHECK (
        jsonb_typeof(access_reasons) = 'array'
        AND access_reasons <@ '[
            "NO_ACCESSIBLE_METADATA",
            "NO_FULL_TEXT_LOCATION_RETURNED",
            "FULL_TEXT_LOCATION_LICENSE_MISSING",
            "FULL_TEXT_LOCATION_LICENSE_REJECTED",
            "FULL_TEXT_LOCATION_URL_REJECTED",
            "FULL_TEXT_DOWNLOAD_FAILED",
            "FULL_TEXT_FORMAT_UNSUPPORTED",
            "FULL_TEXT_PARSE_FAILED",
            "FULL_TEXT_IDENTITY_UNVERIFIED",
            "FULL_TEXT_IDENTITY_MISMATCH",
            "FULL_TEXT_IDENTITY_VALIDATION_FAILED",
            "LANGUAGE_UNSUPPORTED"
        ]'::jsonb
    );
