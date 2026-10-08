ALTER TABLE bibliography_entry_resolutions
    ADD COLUMN candidate_evidence JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD CONSTRAINT bibliography_entry_resolutions_candidate_evidence_array_check
        CHECK (
            CASE
                WHEN jsonb_typeof(candidate_evidence) = 'array'
                    THEN jsonb_array_length(candidate_evidence) <= 3
                ELSE false
            END
        );
