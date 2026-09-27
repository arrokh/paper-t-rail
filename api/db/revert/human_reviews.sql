DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM human_reviews) THEN
        RAISE EXCEPTION 'Cannot revert Human Reviews after they have been recorded';
    END IF;
END;
$$;

DROP TRIGGER human_reviews_are_append_only ON human_reviews;
DROP FUNCTION prevent_human_review_mutation();
DROP TABLE human_reviews;
ALTER TABLE claim_paper_verifications
    DROP CONSTRAINT claim_paper_verifications_run_id_unique;
