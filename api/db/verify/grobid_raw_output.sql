DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
          FROM information_schema.columns
         WHERE table_schema = 'public'
           AND table_name = 'parsed_document_parses'
           AND column_name = 'raw_tei_object_key'
           AND data_type = 'text'
    ) THEN
        RAISE EXCEPTION 'Raw GROBID object key is missing from parsed document provenance';
    END IF;
END;
$$;
