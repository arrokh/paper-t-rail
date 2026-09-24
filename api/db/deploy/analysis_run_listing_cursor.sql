CREATE INDEX analysis_runs_created_at_id_idx
    ON analysis_runs(created_at DESC, id DESC);
