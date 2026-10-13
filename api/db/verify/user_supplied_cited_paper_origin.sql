SELECT access_origin, provider_id, source_url, license_identifier, content_media_type,
       content_sha256, language, language_detector_version, rights_declaration_version,
       rights_declaration_text, rights_declared_at, originating_analysis_run_id,
       originating_recovery_batch_id, originating_recovery_upload_id
  FROM cited_paper_access
 WHERE FALSE;

SELECT 1 / CASE WHEN EXISTS (
    SELECT 1 FROM pg_constraint
     WHERE conrelid = 'cited_paper_access'::regclass
       AND conname = 'cited_paper_access_origin_check'
) AND EXISTS (
    SELECT 1 FROM pg_constraint
     WHERE conrelid = 'cited_paper_access'::regclass
       AND conname = 'cited_paper_access_provider_origin_check'
) AND NOT EXISTS (
    SELECT 1 FROM cited_paper_access
     WHERE NOT (
        (access_origin = 'AUTOMATIC'
            AND provider_id IS NOT NULL
            AND rights_declaration_version IS NULL
            AND rights_declaration_text IS NULL
            AND rights_declared_at IS NULL
            AND originating_analysis_run_id IS NULL
            AND originating_recovery_batch_id IS NULL
            AND originating_recovery_upload_id IS NULL
            AND ((access_status = 'FULL_TEXT_AVAILABLE' AND object_key IS NOT NULL AND content_sha256 IS NOT NULL
                    AND source_url IS NOT NULL AND license_identifier IS NOT NULL AND language_detector_version IS NOT NULL)
                OR (access_status <> 'FULL_TEXT_AVAILABLE' AND object_key IS NULL AND content_sha256 IS NULL
                    AND language IS NULL AND language_detector_version IS NULL)))
        OR (access_origin = 'USER_SUPPLIED' AND access_status = 'FULL_TEXT_AVAILABLE'
            AND provider_id IS NULL AND source_url IS NULL AND license_identifier IS NULL
            AND location_version IS NULL AND location_host_type IS NULL AND content_media_type = 'application/pdf'
            AND object_key IS NOT NULL AND btrim(object_key) <> '' AND content_sha256 IS NOT NULL AND language IS NOT NULL AND btrim(language) <> ''
            AND language_detector_version IS NOT NULL AND btrim(language_detector_version) <> ''
            AND rights_declaration_version IS NOT NULL AND btrim(rights_declaration_version) <> ''
            AND rights_declaration_text IS NOT NULL AND btrim(rights_declaration_text) <> '' AND rights_declared_at IS NOT NULL
            AND originating_analysis_run_id IS NOT NULL AND originating_recovery_batch_id IS NOT NULL
            AND originating_recovery_upload_id IS NOT NULL))
) THEN 1 ELSE 0 END;

CREATE TEMPORARY TABLE cited_paper_access_origin_check_test
    (LIKE cited_paper_access INCLUDING DEFAULTS INCLUDING CONSTRAINTS);

INSERT INTO cited_paper_access_origin_check_test (
    analysis_run_id, bibliography_entry_id, canonical_paper_id, access_status, provider_id,
    metadata_available, abstract_available, source_url, license_identifier, discovered_at,
    object_key, content_sha256, language, language_detector_version
) VALUES (
    gen_random_uuid(), gen_random_uuid(), gen_random_uuid(), 'FULL_TEXT_AVAILABLE', 'fixture-provider',
    TRUE, FALSE, 'https://example.invalid/paper.pdf', 'CC0-1.0', now(), 'fixture/automatic.pdf',
    repeat('a', 64), 'en', 'fixture-detector'
);

INSERT INTO cited_paper_access_origin_check_test (
    analysis_run_id, bibliography_entry_id, canonical_paper_id, access_status, provider_id,
    metadata_available, abstract_available, discovered_at, object_key, content_sha256,
    content_media_type, language, language_detector_version, access_origin,
    rights_declaration_version, rights_declaration_text, rights_declared_at,
    originating_analysis_run_id, originating_recovery_batch_id, originating_recovery_upload_id
) VALUES (
    gen_random_uuid(), gen_random_uuid(), gen_random_uuid(), 'FULL_TEXT_AVAILABLE', NULL,
    TRUE, FALSE, now(), 'fixture/supplied.pdf', repeat('b', 64), 'application/pdf', 'en',
    'fixture-detector', 'USER_SUPPLIED', 'fixture-v1', 'Synthetic private-use declaration', now(),
    gen_random_uuid(), gen_random_uuid(), gen_random_uuid()
);

DO $$
BEGIN
    BEGIN
        INSERT INTO cited_paper_access_origin_check_test (
            analysis_run_id, bibliography_entry_id, canonical_paper_id, access_status, provider_id,
            metadata_available, abstract_available, discovered_at, object_key, content_sha256,
            content_media_type, language, language_detector_version, access_origin,
            rights_declaration_version, rights_declaration_text, rights_declared_at,
            originating_analysis_run_id, originating_recovery_batch_id, originating_recovery_upload_id
        ) VALUES (
            gen_random_uuid(), gen_random_uuid(), gen_random_uuid(), 'FULL_TEXT_AVAILABLE', 'fake-provider',
            TRUE, FALSE, now(), 'fixture/mixed.pdf', repeat('c', 64), 'application/pdf', 'en',
            'fixture-detector', 'USER_SUPPLIED', 'fixture-v1', 'Synthetic private-use declaration', now(),
            gen_random_uuid(), gen_random_uuid(), gen_random_uuid()
        );
        RAISE EXCEPTION 'USER_SUPPLIED with a provider unexpectedly passed its database constraints';
    EXCEPTION WHEN check_violation THEN
        NULL;
    END;
    BEGIN
        INSERT INTO cited_paper_access_origin_check_test (
            analysis_run_id, bibliography_entry_id, canonical_paper_id, access_status, provider_id,
            metadata_available, abstract_available, discovered_at, object_key, content_sha256,
            content_media_type, language, language_detector_version, access_origin,
            rights_declaration_version, rights_declaration_text, rights_declared_at,
            originating_analysis_run_id, originating_recovery_batch_id, originating_recovery_upload_id
        ) VALUES (
            gen_random_uuid(), gen_random_uuid(), gen_random_uuid(), 'FULL_TEXT_AVAILABLE', NULL,
            TRUE, FALSE, now(), 'fixture/missing-rights.pdf', repeat('d', 64), 'application/pdf', 'en',
            'fixture-detector', 'USER_SUPPLIED', NULL, NULL, NULL,
            gen_random_uuid(), gen_random_uuid(), gen_random_uuid()
        );
        RAISE EXCEPTION 'USER_SUPPLIED without a rights snapshot unexpectedly passed its database constraints';
    EXCEPTION WHEN check_violation THEN
        NULL;
    END;
END;
$$;

INSERT INTO cited_paper_access_origin_check_test (
    analysis_run_id, bibliography_entry_id, canonical_paper_id, access_status, provider_id, access_reason,
    metadata_available, abstract_available, discovered_at
) VALUES (
    gen_random_uuid(), gen_random_uuid(), gen_random_uuid(), 'METADATA_ONLY', 'fixture-provider',
    'NO_LEGAL_FULL_TEXT_LOCATION', TRUE, FALSE, now()
);

DO $$
BEGIN
    BEGIN
        INSERT INTO cited_paper_access_origin_check_test (
            analysis_run_id, bibliography_entry_id, canonical_paper_id, access_status, provider_id,
            access_reason, metadata_available, abstract_available, discovered_at, object_key
        ) VALUES (
            gen_random_uuid(), gen_random_uuid(), gen_random_uuid(), 'METADATA_ONLY', 'fixture-provider',
            'NO_LEGAL_FULL_TEXT_LOCATION', TRUE, FALSE, now(), 'fixture/ineligible.pdf'
        );
        RAISE EXCEPTION 'AUTOMATIC non-full-text access with an object key unexpectedly passed its database constraints';
    EXCEPTION WHEN check_violation THEN
        NULL;
    END;
END;
$$;

DO $$
DECLARE
    invalid_case RECORD;
BEGIN
    FOR invalid_case IN
        SELECT * FROM (VALUES
            ('invalid hash', NULL::VARCHAR, NULL::TEXT, NULL::TEXT, 'application/pdf'::VARCHAR, 'not-a-hash'::VARCHAR, 'fixture/invalid-hash.pdf'::TEXT),
            ('non-PDF media type', NULL::VARCHAR, NULL::TEXT, NULL::TEXT, 'text/plain'::VARCHAR, repeat('e', 64)::VARCHAR, 'fixture/non-pdf.txt'::TEXT),
            ('fabricated source URL', NULL::VARCHAR, 'https://example.invalid/supplied.pdf'::TEXT, NULL::TEXT, 'application/pdf'::VARCHAR, repeat('f', 64)::VARCHAR, 'fixture/fake-url.pdf'::TEXT),
            ('fabricated license', NULL::VARCHAR, NULL::TEXT, 'CC0-1.0'::TEXT, 'application/pdf'::VARCHAR, repeat('a', 64)::VARCHAR, 'fixture/fake-license.pdf'::TEXT),
            ('blank object key', NULL::VARCHAR, NULL::TEXT, NULL::TEXT, 'application/pdf'::VARCHAR, repeat('b', 64)::VARCHAR, ''::TEXT)
        ) AS invalid_cases(label, provider_id, source_url, license_identifier, content_media_type, content_sha256, object_key)
    LOOP
        BEGIN
            INSERT INTO cited_paper_access_origin_check_test (
                analysis_run_id, bibliography_entry_id, canonical_paper_id, access_status, provider_id,
                metadata_available, abstract_available, source_url, license_identifier, discovered_at,
                object_key, content_sha256, content_media_type, language, language_detector_version, access_origin,
                rights_declaration_version, rights_declaration_text, rights_declared_at,
                originating_analysis_run_id, originating_recovery_batch_id, originating_recovery_upload_id
            ) VALUES (
                gen_random_uuid(), gen_random_uuid(), gen_random_uuid(), 'FULL_TEXT_AVAILABLE', invalid_case.provider_id,
                TRUE, FALSE, invalid_case.source_url, invalid_case.license_identifier, now(),
                invalid_case.object_key, invalid_case.content_sha256, invalid_case.content_media_type, 'en',
                'fixture-detector', 'USER_SUPPLIED', 'fixture-v1', 'Synthetic private-use declaration', now(),
                gen_random_uuid(), gen_random_uuid(), gen_random_uuid()
            );
            RAISE EXCEPTION 'USER_SUPPLIED % unexpectedly passed its database constraints', invalid_case.label;
        EXCEPTION WHEN check_violation THEN
            NULL;
        END;
    END LOOP;
END;
$$;

CREATE TRIGGER verify_cited_paper_access_immutability
    BEFORE UPDATE ON cited_paper_access_origin_check_test
    FOR EACH ROW EXECUTE FUNCTION public.prevent_cited_paper_access_update();

DO $$
BEGIN
    BEGIN
        UPDATE cited_paper_access_origin_check_test
           SET rights_declaration_text = 'Changed private-use declaration'
         WHERE access_origin = 'USER_SUPPLIED';
        RAISE EXCEPTION 'USER_SUPPLIED rights declaration was unexpectedly mutable';
    EXCEPTION WHEN OTHERS THEN
        IF SQLERRM <> 'Cited Paper access provenance is immutable' THEN
            RAISE;
        END IF;
    END;
END;
$$;
