CREATE TABLE simulation_product_work (
 manifest_id text PRIMARY KEY,
 manifest_sha256 text NOT NULL,
 source_event_id uuid NOT NULL,
 status text NOT NULL DEFAULT 'QUEUED',
 lease_token uuid,
 lease_until timestamptz,
 next_attempt_at timestamptz NOT NULL DEFAULT now(),
 attempts bigint NOT NULL DEFAULT 0,
 last_issue text,
 created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX simulation_product_due ON simulation_product_work(status,next_attempt_at);
