ALTER TABLE bibliography_entry_resolutions
    DROP CONSTRAINT bibliography_entry_resolutions_candidate_evidence_array_check,
    DROP COLUMN candidate_evidence;
