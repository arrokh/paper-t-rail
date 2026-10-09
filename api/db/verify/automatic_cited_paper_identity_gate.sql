DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
          FROM pg_constraint
         WHERE conname = 'cited_paper_access_access_reasons_check'
           AND conrelid = 'public.cited_paper_access'::regclass
           AND pg_get_constraintdef(oid) LIKE '%FULL_TEXT_IDENTITY_UNVERIFIED%'
           AND pg_get_constraintdef(oid) LIKE '%FULL_TEXT_IDENTITY_MISMATCH%'
           AND pg_get_constraintdef(oid) LIKE '%FULL_TEXT_IDENTITY_VALIDATION_FAILED%'
    ) THEN
        RAISE EXCEPTION 'Automatic Cited Paper identity causes are not allowed';
    END IF;
END;
$$;
