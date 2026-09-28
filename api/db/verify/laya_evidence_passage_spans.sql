DO $$
BEGIN
    IF to_regclass('public.laya_evidence_passage_spans') IS NULL THEN
        RAISE EXCEPTION 'Laya Evidence Passage span table is missing';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
         WHERE conname = 'laya_evidence_passage_spans_idempotent_key'
           AND contype = 'u'
    ) OR NOT EXISTS (
        SELECT 1 FROM pg_constraint
         WHERE conname = 'laya_evidence_passage_spans_candidate_scope_fk'
           AND contype = 'f'
           AND confdeltype = 'c'
    ) OR NOT EXISTS (
        SELECT 1 FROM pg_constraint
         WHERE conname = 'laya_evidence_passage_spans_six_token_counts'
           AND contype = 'c'
    ) OR NOT EXISTS (
        SELECT 1 FROM pg_constraint
         WHERE conname = 'laya_evidence_passage_spans_context_contains_core'
           AND contype = 'c'
    ) OR NOT EXISTS (
        SELECT 1 FROM pg_constraint
         WHERE conname = 'laya_evidence_passage_spans_status_shape'
           AND contype = 'c'
    ) THEN
        RAISE EXCEPTION 'Laya Evidence Passage span persistence invariants are missing';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_trigger
         WHERE tgname = 'laya_evidence_passage_spans_have_monotonic_status'
           AND tgrelid = 'public.laya_evidence_passage_spans'::regclass
           AND (tgtype & 19) = 19
           AND NOT tgisinternal
    ) THEN
        RAISE EXCEPTION 'Laya Evidence Passage span provenance is not immutable';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_trigger
         WHERE tgname = 'laya_evidence_passage_spans_require_active_source_document'
           AND tgrelid = 'public.laya_evidence_passage_spans'::regclass
           AND tgfoid = 'reject_deleted_document_analysis_write()'::regprocedure
           AND (tgtype & 19) = 19
           AND NOT tgisinternal
    ) THEN
        RAISE EXCEPTION 'Laya Evidence Passage spans can outlive or mutate deleted source data';
    END IF;
END;
$$;

SELECT id,
       evidence_candidate_id,
       verification_id,
       analysis_run_id,
       bibliography_entry_id,
       splitting_policy_version,
       span_index,
       core_start_offset,
       core_end_offset,
       context_start_offset,
       context_end_offset,
       token_counts,
       system_one_provider,
       system_one_model,
       system_one_version,
       judgement_rubric_version,
       status,
       failure_reason,
       judgement,
       evidence_role,
       confidence,
       directness,
       claim_scope_match,
       study_design_quality,
       relevance,
       raw_scores
  FROM laya_evidence_passage_spans
 WHERE false;
