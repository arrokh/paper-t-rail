LOCK TABLE reference_resolution_entry_chain_states, reference_resolution_provider_attempts,
    reference_resolution_provider_lookup_results IN ACCESS EXCLUSIVE MODE;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM reference_resolution_entry_chain_states)
        OR EXISTS (SELECT 1 FROM reference_resolution_provider_attempts)
        OR EXISTS (SELECT 1 FROM reference_resolution_provider_lookup_results) THEN
        RAISE EXCEPTION 'Refusing to drop reference-resolution chain state while ledger rows exist';
    END IF;
END;
$$;

DROP TABLE reference_resolution_provider_lookup_results;
DROP TABLE reference_resolution_provider_attempts;
DROP TABLE reference_resolution_entry_chain_states;
