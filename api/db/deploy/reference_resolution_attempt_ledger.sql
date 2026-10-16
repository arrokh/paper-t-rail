CREATE TABLE reference_resolution_entry_chain_states (
    analysis_run_id UUID NOT NULL,
    bibliography_entry_id UUID NOT NULL,
    owner_token UUID NOT NULL,
    owner_fence BIGINT NOT NULL CHECK (owner_fence > 0),
    lease_expires_at TIMESTAMPTZ NOT NULL,
    terminal_outcome VARCHAR(48),
    metadata_origin_provider_id VARCHAR(16),
    CONSTRAINT reference_resolution_entry_chain_states_pk
        PRIMARY KEY (analysis_run_id, bibliography_entry_id),
    CONSTRAINT reference_resolution_entry_chain_states_run_entry_fk
        FOREIGN KEY (analysis_run_id, bibliography_entry_id)
        REFERENCES bibliography_entries(analysis_run_id, id) ON DELETE CASCADE,
    CONSTRAINT reference_resolution_entry_chain_states_terminal_outcome_check
        CHECK (terminal_outcome IN (
            'RESOLVED', 'NO_CANDIDATE_OR_DOI_UNCONFIRMED', 'CONFLICT_OR_AMBIGUITY',
            'PROVIDER_UNAVAILABLE', 'BUDGET_OR_DEADLINE_EXHAUSTED',
            'CONSENT_OR_CONFIG_REJECTED', 'NOT_ATTEMPTED', 'UNKNOWN'
        )),
    CONSTRAINT reference_resolution_entry_chain_states_origin_provider_check
        CHECK (metadata_origin_provider_id IN ('crossref', 'datacite')),
    CONSTRAINT reference_resolution_entry_chain_states_active_origin_check
        CHECK (terminal_outcome IS NOT NULL OR metadata_origin_provider_id IS NULL)
);

CREATE TABLE reference_resolution_provider_attempts (
    attempt_id UUID PRIMARY KEY,
    analysis_run_id UUID NOT NULL,
    bibliography_entry_id UUID NOT NULL,
    reservation_ordinal SMALLINT NOT NULL CHECK (reservation_ordinal BETWEEN 1 AND 3),
    lookup_ordinal SMALLINT NOT NULL CHECK (lookup_ordinal IN (1, 2)),
    provider_id VARCHAR(16) NOT NULL CHECK (provider_id IN ('crossref', 'datacite')),
    provider_attempt_ordinal SMALLINT NOT NULL CHECK (provider_attempt_ordinal BETWEEN 1 AND 2),
    owner_token UUID NOT NULL,
    owner_fence BIGINT NOT NULL CHECK (owner_fence > 0),
    state VARCHAR(16) NOT NULL CHECK (state IN ('RESERVED', 'IN_FLIGHT', 'COMPLETED', 'UNKNOWN')),
    outcome_code VARCHAR(32),
    next_attempt_not_before TIMESTAMPTZ,
    http_status SMALLINT CHECK (http_status BETWEEN 100 AND 599),
    duration_millis BIGINT CHECK (duration_millis >= 0),
    response_bytes BIGINT CHECK (response_bytes >= 0),
    reserved_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    unknown_at TIMESTAMPTZ,
    deadline_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT reference_resolution_provider_attempts_run_entry_fk
        FOREIGN KEY (analysis_run_id, bibliography_entry_id)
        REFERENCES bibliography_entries(analysis_run_id, id) ON DELETE CASCADE,
    CONSTRAINT reference_resolution_provider_attempts_chain_state_fk
        FOREIGN KEY (analysis_run_id, bibliography_entry_id)
        REFERENCES reference_resolution_entry_chain_states(analysis_run_id, bibliography_entry_id) ON DELETE CASCADE,
    CONSTRAINT reference_resolution_provider_attempts_outcome_code_check
        CHECK (outcome_code IN (
            'FOUND', 'NOT_FOUND', 'UNUSABLE', 'RETRYABLE_FAILURE', 'PERMANENT_FAILURE', 'UNKNOWN', 'NOT_SENT'
        )),
    CONSTRAINT reference_resolution_provider_attempts_retry_schedule_check
        CHECK (next_attempt_not_before IS NULL OR (
            state = 'COMPLETED' AND outcome_code = 'RETRYABLE_FAILURE' AND provider_attempt_ordinal = 1
        )),
    CONSTRAINT reference_resolution_provider_attempts_provider_lookup_check
        CHECK (
            (lookup_ordinal = 1 AND provider_id = 'crossref')
            OR (lookup_ordinal = 2 AND provider_id = 'datacite')
        ),
    CONSTRAINT reference_resolution_provider_attempts_reservation_unique
        UNIQUE (analysis_run_id, bibliography_entry_id, reservation_ordinal),
    CONSTRAINT reference_resolution_provider_attempts_provider_ordinal_unique
        UNIQUE (analysis_run_id, bibliography_entry_id, provider_id, provider_attempt_ordinal),
    CONSTRAINT reference_resolution_provider_attempts_state_shape_check
        CHECK (
            (state = 'RESERVED' AND outcome_code IS NULL AND started_at IS NULL AND completed_at IS NULL AND unknown_at IS NULL)
            OR (state = 'IN_FLIGHT' AND outcome_code IS NULL AND started_at IS NOT NULL AND completed_at IS NULL AND unknown_at IS NULL)
            OR (state = 'COMPLETED' AND outcome_code = 'NOT_SENT' AND started_at IS NULL AND completed_at IS NOT NULL AND unknown_at IS NULL)
            OR (state = 'COMPLETED' AND outcome_code IS NOT NULL AND outcome_code NOT IN ('UNKNOWN', 'NOT_SENT') AND started_at IS NOT NULL AND completed_at IS NOT NULL AND unknown_at IS NULL)
            OR (state = 'UNKNOWN' AND outcome_code = 'UNKNOWN' AND started_at IS NOT NULL AND completed_at IS NULL AND unknown_at IS NOT NULL)
        )
);

CREATE TABLE reference_resolution_provider_lookup_results (
    analysis_run_id UUID NOT NULL,
    bibliography_entry_id UUID NOT NULL,
    lookup_ordinal SMALLINT NOT NULL,
    provider_id VARCHAR(16) NOT NULL,
    source VARCHAR(8) NOT NULL,
    outcome_code VARCHAR(32) NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT reference_resolution_provider_lookup_results_pk
        PRIMARY KEY (analysis_run_id, bibliography_entry_id, lookup_ordinal),
    CONSTRAINT reference_resolution_provider_lookup_results_run_entry_fk
        FOREIGN KEY (analysis_run_id, bibliography_entry_id)
        REFERENCES bibliography_entries(analysis_run_id, id) ON DELETE CASCADE,
    CONSTRAINT reference_resolution_provider_lookup_results_chain_state_fk
        FOREIGN KEY (analysis_run_id, bibliography_entry_id)
        REFERENCES reference_resolution_entry_chain_states(analysis_run_id, bibliography_entry_id) ON DELETE CASCADE,
    CONSTRAINT reference_resolution_provider_lookup_results_provider_lookup_check
        CHECK (
            (lookup_ordinal = 1 AND provider_id = 'crossref')
            OR (lookup_ordinal = 2 AND provider_id = 'datacite')
        ),
    CONSTRAINT reference_resolution_provider_lookup_results_source_check
        CHECK (source IN ('CACHE', 'WIRE')),
    CONSTRAINT reference_resolution_provider_lookup_results_outcome_check
        CHECK (outcome_code IN ('FOUND', 'NOT_FOUND', 'UNUSABLE', 'RETRYABLE_FAILURE', 'PERMANENT_FAILURE')),
    CONSTRAINT reference_resolution_provider_lookup_results_cache_policy_check
        CHECK (source <> 'CACHE' OR (provider_id = 'crossref' AND outcome_code IN ('FOUND', 'NOT_FOUND')))
);
