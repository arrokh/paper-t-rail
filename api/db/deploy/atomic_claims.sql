DROP TRIGGER citation_targets_are_immutable ON citation_targets;

ALTER TABLE citation_targets
    ADD COLUMN citation_context_id UUID;

UPDATE citation_targets AS target
   SET citation_context_id = occurrence.citation_context_id
  FROM citation_occurrences AS occurrence
 WHERE occurrence.analysis_run_id = target.analysis_run_id
   AND occurrence.id = target.citation_occurrence_id;

ALTER TABLE citation_occurrences
    ADD CONSTRAINT citation_occurrences_run_id_context_unique
        UNIQUE (analysis_run_id, id, citation_context_id);

ALTER TABLE citation_targets
    ALTER COLUMN citation_context_id SET NOT NULL,
    ADD CONSTRAINT citation_targets_run_context_fk
        FOREIGN KEY (analysis_run_id, citation_context_id)
        REFERENCES citation_contexts(analysis_run_id, id) ON DELETE CASCADE,
    ADD CONSTRAINT citation_targets_run_id_context_unique
        UNIQUE (analysis_run_id, id, citation_context_id);

ALTER TABLE citation_targets
    ADD CONSTRAINT citation_targets_run_occurrence_context_fk
        FOREIGN KEY (analysis_run_id, citation_occurrence_id, citation_context_id)
        REFERENCES citation_occurrences(analysis_run_id, id, citation_context_id) ON DELETE CASCADE;

CREATE TRIGGER citation_targets_are_immutable
    BEFORE UPDATE ON citation_targets
    FOR EACH ROW EXECUTE FUNCTION prevent_parsed_structure_update();

CREATE TABLE atomic_claims (
    id UUID PRIMARY KEY,
    analysis_run_id UUID NOT NULL,
    citation_context_id UUID NOT NULL,
    claim_text TEXT NOT NULL CHECK (length(btrim(claim_text)) > 0),
    source_start_offset INTEGER NOT NULL CHECK (source_start_offset >= 0),
    source_end_offset INTEGER NOT NULL CHECK (source_end_offset > source_start_offset),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (analysis_run_id, id, citation_context_id),
    CONSTRAINT atomic_claims_run_context_source_span_unique
        UNIQUE (analysis_run_id, citation_context_id, source_start_offset, source_end_offset),
    FOREIGN KEY (analysis_run_id, citation_context_id)
        REFERENCES citation_contexts(analysis_run_id, id) ON DELETE CASCADE
);

CREATE FUNCTION enforce_atomic_claim_context_span() RETURNS trigger AS $$
DECLARE
    context_start INTEGER;
    context_end INTEGER;
BEGIN
    SELECT start_offset, end_offset
      INTO context_start, context_end
      FROM citation_contexts
     WHERE analysis_run_id = NEW.analysis_run_id
       AND id = NEW.citation_context_id;
    IF NOT FOUND OR NEW.source_start_offset < context_start OR NEW.source_end_offset > context_end THEN
        RAISE EXCEPTION 'Atomic Claim source span must be contained by its Citation Context';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER atomic_claim_source_span_is_context_scoped
    BEFORE INSERT ON atomic_claims
    FOR EACH ROW EXECUTE FUNCTION enforce_atomic_claim_context_span();

CREATE TRIGGER atomic_claims_are_immutable
    BEFORE UPDATE ON atomic_claims
    FOR EACH ROW EXECUTE FUNCTION prevent_parsed_structure_update();

CREATE TABLE atomic_claim_citation_targets (
    id UUID PRIMARY KEY,
    analysis_run_id UUID NOT NULL,
    citation_context_id UUID NOT NULL,
    atomic_claim_id UUID NOT NULL,
    citation_target_id UUID NOT NULL,
    association_kind VARCHAR(32) NOT NULL CHECK (association_kind = 'INFERRED_PROVISIONAL'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (analysis_run_id, atomic_claim_id, citation_target_id),
    CONSTRAINT atomic_claim_target_claim_context_fk
        FOREIGN KEY (analysis_run_id, atomic_claim_id, citation_context_id)
        REFERENCES atomic_claims(analysis_run_id, id, citation_context_id) ON DELETE CASCADE,
    CONSTRAINT atomic_claim_target_target_context_fk
        FOREIGN KEY (analysis_run_id, citation_target_id, citation_context_id)
        REFERENCES citation_targets(analysis_run_id, id, citation_context_id) ON DELETE CASCADE
);

CREATE INDEX atomic_claims_run_context_span_idx
    ON atomic_claims(analysis_run_id, citation_context_id, source_start_offset);
CREATE INDEX atomic_claim_citation_targets_run_claim_idx
    ON atomic_claim_citation_targets(analysis_run_id, atomic_claim_id);

CREATE TRIGGER atomic_claim_citation_targets_are_immutable
    BEFORE UPDATE ON atomic_claim_citation_targets
    FOR EACH ROW EXECUTE FUNCTION prevent_parsed_structure_update();
