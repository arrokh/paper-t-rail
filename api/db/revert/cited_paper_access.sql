DROP TRIGGER claim_paper_verifications_have_monotonic_status ON claim_paper_verifications;
DROP FUNCTION enforce_claim_paper_verification_transition();
DROP TABLE claim_paper_verifications;
DROP TRIGGER cited_paper_access_are_immutable ON cited_paper_access;
DROP FUNCTION prevent_cited_paper_access_update();
DROP TABLE cited_paper_access;
ALTER TABLE bibliography_entry_resolutions
    DROP CONSTRAINT bibliography_entry_resolutions_run_paper_unique;
ALTER TABLE atomic_claims
    DROP CONSTRAINT atomic_claims_run_id_unique;
