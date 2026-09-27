BEGIN;

SELECT document_id, deleted_at
  FROM source_document_tombstones
 LIMIT 0;

SELECT analysis_run_id
  FROM inbox_events
 LIMIT 0;

SELECT 1
  FROM pg_constraint
 WHERE conname = 'inbox_events_analysis_run_id_fkey'
   AND confdeltype = 'c';

SELECT 1
  FROM pg_trigger
 WHERE tgname = 'source_documents_cannot_be_recreated_after_deletion'
   AND NOT tgisinternal;

SELECT 1
  FROM pg_trigger
 WHERE tgname = 'analysis_runs_require_active_source_document'
   AND NOT tgisinternal;

SELECT 1
  FROM pg_constraint
 WHERE conname = 'cited_paper_indexing_parse_fk'
   AND confdeltype = 'c';

ROLLBACK;
