DO $$
BEGIN
    EXECUTE 'LOCK TABLE reference_resolution_entry_chain_states, reference_resolution_provider_attempts, reference_resolution_provider_lookup_results IN ACCESS EXCLUSIVE MODE';

    IF EXISTS (SELECT 1 FROM reference_resolution_entry_chain_states)
        OR EXISTS (SELECT 1 FROM reference_resolution_provider_attempts)
        OR EXISTS (SELECT 1 FROM reference_resolution_provider_lookup_results) THEN
        RAISE EXCEPTION 'Refusing to drop reference-resolution chain state while ledger rows exist';
    END IF;

    EXECUTE 'DROP TABLE reference_resolution_provider_lookup_results';
    EXECUTE 'DROP TABLE reference_resolution_provider_attempts';
    EXECUTE 'DROP TABLE reference_resolution_entry_chain_states';
END;
$$;
