ALTER TABLE atomic_claims
    ADD CONSTRAINT atomic_claims_run_id_unique UNIQUE (analysis_run_id, id);

ALTER TABLE bibliography_entry_resolutions
    ADD CONSTRAINT bibliography_entry_resolutions_run_paper_unique
        UNIQUE (analysis_run_id, bibliography_entry_id, canonical_paper_id);

CREATE TABLE cited_paper_access (
    analysis_run_id UUID NOT NULL,
    bibliography_entry_id UUID NOT NULL,
    canonical_paper_id UUID NOT NULL REFERENCES canonical_papers(id),
    access_status VARCHAR(32) NOT NULL CHECK (access_status IN (
        'FULL_TEXT_AVAILABLE', 'ABSTRACT_ONLY', 'METADATA_ONLY', 'UNAVAILABLE'
    )),
    access_reason VARCHAR(64) CHECK (access_reason IS NULL OR access_reason IN (
        'ABSTRACT_ONLY', 'NO_LEGAL_FULL_TEXT_LOCATION', 'NO_ACCESSIBLE_METADATA', 'FULL_TEXT_ACQUISITION_FAILED'
    )),
    provider_id VARCHAR(80) NOT NULL,
    metadata_available BOOLEAN NOT NULL,
    abstract_available BOOLEAN NOT NULL,
    source_url TEXT,
    license_identifier TEXT,
    location_version VARCHAR(80),
    location_host_type VARCHAR(80),
    discovered_at TIMESTAMPTZ NOT NULL,
    object_key TEXT,
    content_sha256 VARCHAR(64) CHECK (content_sha256 IS NULL OR content_sha256 ~ '^[0-9a-f]{64}$'),
    language VARCHAR(16),
    language_detector_version VARCHAR(80),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (analysis_run_id, bibliography_entry_id),
    CONSTRAINT cited_paper_access_run_reference_paper_unique
        UNIQUE (analysis_run_id, bibliography_entry_id, canonical_paper_id),
    CONSTRAINT cited_paper_access_run_paper_fk
        FOREIGN KEY (analysis_run_id, bibliography_entry_id, canonical_paper_id)
        REFERENCES bibliography_entry_resolutions(analysis_run_id, bibliography_entry_id, canonical_paper_id) ON DELETE CASCADE,
    CHECK (
        (access_status = 'FULL_TEXT_AVAILABLE' AND object_key IS NOT NULL AND content_sha256 IS NOT NULL
            AND source_url IS NOT NULL AND license_identifier IS NOT NULL AND language_detector_version IS NOT NULL)
        OR (access_status <> 'FULL_TEXT_AVAILABLE' AND object_key IS NULL AND content_sha256 IS NULL
            AND language IS NULL AND language_detector_version IS NULL)
    ),
    CHECK (access_status <> 'ABSTRACT_ONLY' OR (metadata_available AND abstract_available)),
    CHECK (access_status <> 'METADATA_ONLY' OR (metadata_available AND NOT abstract_available)),
    CHECK (access_status <> 'UNAVAILABLE' OR (NOT metadata_available AND NOT abstract_available)),
    CHECK (access_status <> 'FULL_TEXT_AVAILABLE' OR metadata_available),
    CHECK ((access_status = 'FULL_TEXT_AVAILABLE') = (access_reason IS NULL))
);

CREATE INDEX cited_paper_access_run_status_idx
    ON cited_paper_access(analysis_run_id, access_status, bibliography_entry_id);

CREATE FUNCTION prevent_cited_paper_access_update() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'Cited Paper access provenance is immutable';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER cited_paper_access_are_immutable
    BEFORE UPDATE ON cited_paper_access
    FOR EACH ROW EXECUTE FUNCTION prevent_cited_paper_access_update();

CREATE TABLE claim_paper_verifications (
    id UUID PRIMARY KEY,
    analysis_run_id UUID NOT NULL,
    atomic_claim_id UUID NOT NULL,
    bibliography_entry_id UUID NOT NULL,
    canonical_paper_id UUID NOT NULL,
    processing_status VARCHAR(16) NOT NULL CHECK (processing_status IN ('PENDING', 'COMPLETED')),
    verification_scope VARCHAR(16) NOT NULL CHECK (verification_scope IN ('FULL_TEXT', 'ABSTRACT_ONLY', 'NONE')),
    terminal_reason VARCHAR(64),
    final_status VARCHAR(40) CHECK (final_status IS NULL OR final_status IN (
        'SUPPORTED', 'PARTIALLY_SUPPORTED', 'CONTRADICTED', 'INSUFFICIENT_EVIDENCE',
        'INACCESSIBLE', 'UNRESOLVED', 'UNSUPPORTED_REFERENCE_TYPE'
    )),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (analysis_run_id, atomic_claim_id, bibliography_entry_id),
    CONSTRAINT claim_paper_verifications_same_run_claim_fk
        FOREIGN KEY (analysis_run_id, atomic_claim_id)
        REFERENCES atomic_claims(analysis_run_id, id) ON DELETE CASCADE,
    CONSTRAINT claim_paper_verifications_access_paper_fk
        FOREIGN KEY (analysis_run_id, bibliography_entry_id, canonical_paper_id)
        REFERENCES cited_paper_access(analysis_run_id, bibliography_entry_id, canonical_paper_id) ON DELETE CASCADE,
    CHECK ((processing_status = 'PENDING') = (final_status IS NULL))
);

CREATE INDEX claim_paper_verifications_run_status_idx
    ON claim_paper_verifications(analysis_run_id, processing_status, final_status);

CREATE FUNCTION enforce_claim_paper_verification_transition() RETURNS trigger AS $$
BEGIN
    IF NEW.id IS DISTINCT FROM OLD.id
       OR NEW.analysis_run_id IS DISTINCT FROM OLD.analysis_run_id
       OR NEW.atomic_claim_id IS DISTINCT FROM OLD.atomic_claim_id
       OR NEW.bibliography_entry_id IS DISTINCT FROM OLD.bibliography_entry_id
       OR NEW.canonical_paper_id IS DISTINCT FROM OLD.canonical_paper_id
       OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'Claim–Paper Verification provenance is immutable';
    END IF;
    IF OLD.processing_status <> 'PENDING' OR NEW.processing_status <> 'COMPLETED'
       OR NEW.final_status IS NULL OR NEW.verification_scope <> OLD.verification_scope THEN
        RAISE EXCEPTION 'Invalid Claim–Paper Verification transition';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER claim_paper_verifications_have_monotonic_status
    BEFORE UPDATE ON claim_paper_verifications
    FOR EACH ROW EXECUTE FUNCTION enforce_claim_paper_verification_transition();
