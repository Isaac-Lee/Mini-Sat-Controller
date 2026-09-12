CREATE TABLE simulation_payload_work (
 intent_id text PRIMARY KEY,
 status text NOT NULL DEFAULT 'QUEUED',
 lease_token uuid,
 lease_until timestamptz,
 next_attempt_at timestamptz NOT NULL DEFAULT now(),
 attempts bigint NOT NULL DEFAULT 0,
 last_issue text
);
CREATE INDEX simulation_payload_due ON simulation_payload_work(status,next_attempt_at);
