ALTER TABLE cited_paper_access
    DROP CONSTRAINT cited_paper_access_access_reasons_check,
    DROP COLUMN access_reasons;
