CREATE TABLE outbox_events (
    event_id uuid PRIMARY KEY,
    analysis_run_id uuid NOT NULL,
    correlation_id uuid NOT NULL,
    payload jsonb NOT NULL,
    published_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    publish_attempts integer NOT NULL DEFAULT 0
);
