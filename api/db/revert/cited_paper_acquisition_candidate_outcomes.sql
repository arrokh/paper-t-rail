ALTER TABLE cited_paper_access
    DROP CONSTRAINT cited_paper_access_candidate_outcomes_check,
    DROP CONSTRAINT cited_paper_access_candidate_outcomes_omitted_count_check,
    DROP COLUMN candidate_outcomes,
    DROP COLUMN candidate_outcomes_omitted_count;
