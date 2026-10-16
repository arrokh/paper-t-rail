DO $$
BEGIN
    IF to_regclass('public.reference_resolution_entry_chain_states') IS NULL THEN
        RAISE EXCEPTION 'Reference-resolution entry chain-state table is missing';
    END IF;
    IF to_regclass('public.reference_resolution_provider_attempts') IS NULL THEN
        RAISE EXCEPTION 'Reference-resolution provider-attempt ledger table is missing';
    END IF;
    IF to_regclass('public.reference_resolution_provider_lookup_results') IS NULL THEN
        RAISE EXCEPTION 'Reference-resolution logical lookup-result table is missing';
    END IF;

    IF NOT EXISTS (
        SELECT 1
          FROM pg_constraint
         WHERE conname = 'reference_resolution_entry_chain_states_run_entry_fk'
           AND conrelid = 'public.reference_resolution_entry_chain_states'::regclass
           AND contype = 'f'
           AND pg_get_constraintdef(oid) LIKE '%FOREIGN KEY (analysis_run_id, bibliography_entry_id)%'
           AND pg_get_constraintdef(oid) LIKE '%REFERENCES bibliography_entries(analysis_run_id, id)%'
           AND pg_get_constraintdef(oid) LIKE '%ON DELETE CASCADE%'
    ) THEN
        RAISE EXCEPTION 'Reference-resolution chain-state run-local bibliography foreign key is missing';
    END IF;

    IF NOT EXISTS (
        SELECT 1
          FROM pg_constraint
         WHERE conname = 'reference_resolution_entry_chain_states_pk'
           AND conrelid = 'public.reference_resolution_entry_chain_states'::regclass
           AND contype = 'p'
           AND pg_get_constraintdef(oid) LIKE '%analysis_run_id, bibliography_entry_id%'
    ) THEN
        RAISE EXCEPTION 'Reference-resolution chain-state entry key is missing';
    END IF;

    IF NOT EXISTS (
        SELECT 1
          FROM information_schema.columns
         WHERE table_schema = 'public'
           AND table_name = 'reference_resolution_entry_chain_states'
           AND column_name = 'owner_token'
           AND data_type = 'uuid'
           AND is_nullable = 'NO'
    ) OR NOT EXISTS (
        SELECT 1
          FROM information_schema.columns
         WHERE table_schema = 'public'
           AND table_name = 'reference_resolution_entry_chain_states'
           AND column_name = 'owner_fence'
           AND data_type = 'bigint'
           AND is_nullable = 'NO'
    ) OR NOT EXISTS (
        SELECT 1
          FROM information_schema.columns
         WHERE table_schema = 'public'
           AND table_name = 'reference_resolution_entry_chain_states'
           AND column_name = 'lease_expires_at'
           AND data_type = 'timestamp with time zone'
           AND is_nullable = 'NO'
    ) THEN
        RAISE EXCEPTION 'Reference-resolution chain owner token/fence/DB lease columns are missing';
    END IF;

    IF NOT EXISTS (
        SELECT 1
          FROM pg_constraint
         WHERE conname = 'reference_resolution_entry_chain_states_terminal_outcome_check'
           AND conrelid = 'public.reference_resolution_entry_chain_states'::regclass
           AND contype = 'c'
           AND pg_get_constraintdef(oid) LIKE '%RESOLVED%NO_CANDIDATE_OR_DOI_UNCONFIRMED%CONFLICT_OR_AMBIGUITY%PROVIDER_UNAVAILABLE%BUDGET_OR_DEADLINE_EXHAUSTED%CONSENT_OR_CONFIG_REJECTED%NOT_ATTEMPTED%UNKNOWN%'
    ) THEN
        RAISE EXCEPTION 'Reference-resolution chain terminal outcome allowlist is missing';
    END IF;

    IF NOT EXISTS (
        SELECT 1
          FROM information_schema.columns
         WHERE table_schema = 'public'
           AND table_name = 'reference_resolution_provider_attempts'
           AND column_name = 'owner_token'
           AND data_type = 'uuid'
           AND is_nullable = 'NO'
    ) OR NOT EXISTS (
        SELECT 1
          FROM information_schema.columns
         WHERE table_schema = 'public'
           AND table_name = 'reference_resolution_provider_attempts'
           AND column_name = 'owner_fence'
           AND data_type = 'bigint'
           AND is_nullable = 'NO'
    ) THEN
        RAISE EXCEPTION 'Reference-resolution attempts do not carry their reserving owner authorization';
    END IF;

    IF NOT EXISTS (
        SELECT 1
          FROM pg_constraint
         WHERE conname = 'reference_resolution_provider_attempts_run_entry_fk'
           AND conrelid = 'public.reference_resolution_provider_attempts'::regclass
           AND contype = 'f'
           AND pg_get_constraintdef(oid) LIKE '%FOREIGN KEY (analysis_run_id, bibliography_entry_id)%'
           AND pg_get_constraintdef(oid) LIKE '%REFERENCES bibliography_entries(analysis_run_id, id)%'
           AND pg_get_constraintdef(oid) LIKE '%ON DELETE CASCADE%'
    ) THEN
        RAISE EXCEPTION 'Reference-resolution attempt ledger run-local bibliography foreign key is missing';
    END IF;

    IF NOT EXISTS (
        SELECT 1
          FROM pg_constraint
         WHERE conname = 'reference_resolution_provider_attempts_chain_state_fk'
           AND conrelid = 'public.reference_resolution_provider_attempts'::regclass
           AND contype = 'f'
           AND pg_get_constraintdef(oid) LIKE '%REFERENCES reference_resolution_entry_chain_states(analysis_run_id, bibliography_entry_id)%'
           AND pg_get_constraintdef(oid) LIKE '%ON DELETE CASCADE%'
    ) THEN
        RAISE EXCEPTION 'Reference-resolution attempt-to-owner-state foreign key is missing';
    END IF;

    IF NOT EXISTS (
        SELECT 1
          FROM pg_constraint
         WHERE conname = 'reference_resolution_provider_attempts_retry_schedule_check'
           AND conrelid = 'public.reference_resolution_provider_attempts'::regclass
           AND contype = 'c'
           AND pg_get_constraintdef(oid) LIKE '%next_attempt_not_before%RETRYABLE_FAILURE%'
    ) THEN
        RAISE EXCEPTION 'Reference-resolution retry not-before constraint is missing';
    END IF;

    IF NOT EXISTS (
        SELECT 1
          FROM pg_constraint
         WHERE conname = 'reference_resolution_provider_attempts_outcome_code_check'
           AND conrelid = 'public.reference_resolution_provider_attempts'::regclass
           AND contype = 'c'
           AND pg_get_constraintdef(oid) LIKE '%FOUND%NOT_FOUND%UNUSABLE%RETRYABLE_FAILURE%PERMANENT_FAILURE%UNKNOWN%NOT_SENT%'
    ) THEN
        RAISE EXCEPTION 'Reference-resolution attempt outcome allowlist is missing';
    END IF;

    IF NOT EXISTS (
        SELECT 1
          FROM information_schema.columns
         WHERE table_schema = 'public'
           AND table_name = 'reference_resolution_provider_attempts'
           AND column_name = 'reserved_at'
           AND data_type = 'timestamp with time zone'
           AND is_nullable = 'NO'
    ) OR NOT EXISTS (
        SELECT 1
          FROM information_schema.columns
         WHERE table_schema = 'public'
           AND table_name = 'reference_resolution_provider_attempts'
           AND column_name = 'deadline_at'
           AND data_type = 'timestamp with time zone'
           AND is_nullable = 'NO'
    ) OR NOT EXISTS (
        SELECT 1
          FROM information_schema.columns
         WHERE table_schema = 'public'
           AND table_name = 'reference_resolution_provider_attempts'
           AND column_name = 'next_attempt_not_before'
           AND data_type = 'timestamp with time zone'
           AND is_nullable = 'YES'
    ) THEN
        RAISE EXCEPTION 'Reference-resolution attempt reservation/deadline/retry timestamps are missing';
    END IF;

    IF NOT EXISTS (
        SELECT 1
          FROM pg_constraint
         WHERE conname = 'reference_resolution_provider_lookup_results_run_entry_fk'
           AND conrelid = 'public.reference_resolution_provider_lookup_results'::regclass
           AND contype = 'f'
           AND pg_get_constraintdef(oid) LIKE '%FOREIGN KEY (analysis_run_id, bibliography_entry_id)%'
           AND pg_get_constraintdef(oid) LIKE '%REFERENCES bibliography_entries(analysis_run_id, id)%'
           AND pg_get_constraintdef(oid) LIKE '%ON DELETE CASCADE%'
    ) OR NOT EXISTS (
        SELECT 1
          FROM pg_constraint
         WHERE conname = 'reference_resolution_provider_lookup_results_chain_state_fk'
           AND conrelid = 'public.reference_resolution_provider_lookup_results'::regclass
           AND contype = 'f'
           AND pg_get_constraintdef(oid) LIKE '%REFERENCES reference_resolution_entry_chain_states(analysis_run_id, bibliography_entry_id)%'
           AND pg_get_constraintdef(oid) LIKE '%ON DELETE CASCADE%'
    ) THEN
        RAISE EXCEPTION 'Logical lookup results lack run-local entry or owner-state foreign keys';
    END IF;

    IF NOT EXISTS (
        SELECT 1
          FROM pg_constraint
         WHERE conname = 'reference_resolution_provider_lookup_results_pk'
           AND conrelid = 'public.reference_resolution_provider_lookup_results'::regclass
           AND contype = 'p'
           AND pg_get_constraintdef(oid) LIKE '%analysis_run_id, bibliography_entry_id, lookup_ordinal%'
    ) THEN
        RAISE EXCEPTION 'Logical lookup results do not have a per-entry lookup key';
    END IF;

    IF NOT EXISTS (
        SELECT 1
          FROM pg_constraint
         WHERE conname = 'reference_resolution_provider_lookup_results_provider_lookup_check'
           AND conrelid = 'public.reference_resolution_provider_lookup_results'::regclass
           AND contype = 'c'
           AND pg_get_constraintdef(oid) LIKE '%lookup_ordinal = 1%crossref%lookup_ordinal = 2%datacite%'
    ) THEN
        RAISE EXCEPTION 'Logical lookup ordinal/provider pairs are not fixed';
    END IF;

    IF NOT EXISTS (
        SELECT 1
          FROM pg_constraint
         WHERE conname = 'reference_resolution_provider_lookup_results_source_check'
           AND conrelid = 'public.reference_resolution_provider_lookup_results'::regclass
           AND contype = 'c'
           AND pg_get_constraintdef(oid) LIKE '%CACHE%WIRE%'
    ) OR NOT EXISTS (
        SELECT 1
          FROM pg_constraint
         WHERE conname = 'reference_resolution_provider_lookup_results_cache_policy_check'
           AND conrelid = 'public.reference_resolution_provider_lookup_results'::regclass
           AND contype = 'c'
           AND pg_get_constraintdef(oid) LIKE '%crossref%FOUND%NOT_FOUND%'
    ) OR NOT EXISTS (
        SELECT 1
          FROM pg_constraint
         WHERE conname = 'reference_resolution_provider_lookup_results_outcome_check'
           AND conrelid = 'public.reference_resolution_provider_lookup_results'::regclass
           AND contype = 'c'
           AND pg_get_constraintdef(oid) LIKE '%FOUND%NOT_FOUND%UNUSABLE%RETRYABLE_FAILURE%PERMANENT_FAILURE%'
    ) THEN
        RAISE EXCEPTION 'Logical lookup source/outcome/cache policy allowlists are missing';
    END IF;

    IF NOT EXISTS (
        SELECT 1
          FROM information_schema.columns
         WHERE table_schema = 'public'
           AND table_name = 'reference_resolution_provider_lookup_results'
           AND column_name = 'lookup_ordinal'
           AND data_type = 'smallint'
           AND is_nullable = 'NO'
    ) OR NOT EXISTS (
        SELECT 1
          FROM information_schema.columns
         WHERE table_schema = 'public'
           AND table_name = 'reference_resolution_provider_lookup_results'
           AND column_name = 'provider_id'
           AND data_type = 'character varying'
           AND is_nullable = 'NO'
    ) OR NOT EXISTS (
        SELECT 1
          FROM information_schema.columns
         WHERE table_schema = 'public'
           AND table_name = 'reference_resolution_provider_lookup_results'
           AND column_name = 'source'
           AND data_type = 'character varying'
           AND is_nullable = 'NO'
    ) OR NOT EXISTS (
        SELECT 1
          FROM information_schema.columns
         WHERE table_schema = 'public'
           AND table_name = 'reference_resolution_provider_lookup_results'
           AND column_name = 'outcome_code'
           AND data_type = 'character varying'
           AND is_nullable = 'NO'
    ) OR NOT EXISTS (
        SELECT 1
          FROM information_schema.columns
         WHERE table_schema = 'public'
           AND table_name = 'reference_resolution_provider_lookup_results'
           AND column_name = 'recorded_at'
           AND data_type = 'timestamp with time zone'
           AND is_nullable = 'NO'
    ) THEN
        RAISE EXCEPTION 'Logical lookup result timestamp is missing';
    END IF;

    IF EXISTS (
        SELECT 1
          FROM information_schema.columns
         WHERE table_schema = 'public'
           AND table_name IN (
               'reference_resolution_entry_chain_states',
               'reference_resolution_provider_attempts',
               'reference_resolution_provider_lookup_results'
           )
           AND data_type IN ('text', 'json', 'jsonb')
    ) THEN
        RAISE EXCEPTION 'Reference-resolution attempt ledger contains unsafe free-form columns';
    END IF;
END;
$$;
