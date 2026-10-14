ALTER TABLE cited_paper_access
    ADD COLUMN candidate_outcomes JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN candidate_outcomes_omitted_count INTEGER NOT NULL DEFAULT 0,
    ADD CONSTRAINT cited_paper_access_candidate_outcomes_check CHECK (
        jsonb_typeof(candidate_outcomes) = 'array'
        AND jsonb_array_length(candidate_outcomes) <= 5
    ),
    ADD CONSTRAINT cited_paper_access_candidate_outcomes_omitted_count_check CHECK (
        candidate_outcomes_omitted_count >= 0
    );
