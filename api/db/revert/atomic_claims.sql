DROP TRIGGER atomic_claim_citation_targets_are_immutable ON atomic_claim_citation_targets;
DROP INDEX atomic_claim_citation_targets_run_claim_idx;
DROP INDEX atomic_claims_run_context_span_idx;
DROP TABLE atomic_claim_citation_targets;
DROP TRIGGER atomic_claims_are_immutable ON atomic_claims;
DROP TRIGGER atomic_claim_source_span_is_context_scoped ON atomic_claims;
DROP TABLE atomic_claims;
DROP FUNCTION enforce_atomic_claim_context_span();
DROP TRIGGER citation_targets_are_immutable ON citation_targets;
ALTER TABLE citation_targets
    DROP CONSTRAINT citation_targets_run_occurrence_context_fk,
    DROP CONSTRAINT citation_targets_run_id_context_unique,
    DROP CONSTRAINT citation_targets_run_context_fk,
    DROP COLUMN citation_context_id;
ALTER TABLE citation_occurrences
    DROP CONSTRAINT citation_occurrences_run_id_context_unique;
CREATE TRIGGER citation_targets_are_immutable
    BEFORE UPDATE ON citation_targets
    FOR EACH ROW EXECUTE FUNCTION prevent_parsed_structure_update();
