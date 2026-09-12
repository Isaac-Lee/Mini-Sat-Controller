CREATE TABLE simulation_source_work (
 receipt_id text PRIMARY KEY,
 receipt_sha256 text NOT NULL,
 source_event_id uuid NOT NULL,
 status text NOT NULL DEFAULT 'QUEUED',
 lease_token uuid,
 lease_until timestamptz,
 next_attempt_at timestamptz NOT NULL DEFAULT now(),
 attempts bigint NOT NULL DEFAULT 0,
 last_issue text,
 created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX simulation_source_due ON simulation_source_work(status,next_attempt_at);
