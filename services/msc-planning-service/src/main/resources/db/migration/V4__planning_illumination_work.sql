CREATE TABLE planning_illumination_work (
 run_id text PRIMARY KEY,
 request_id text NOT NULL,
 request_revision bigint NOT NULL,
 input_attempt_id text NOT NULL,
 status text NOT NULL DEFAULT 'QUEUED',
 assumptions_version bigint CHECK (assumptions_version > 0),
 lease_token uuid,
 lease_until timestamptz,
 next_attempt_at timestamptz NOT NULL DEFAULT now(),
 attempts bigint NOT NULL DEFAULT 0,
 last_issue text,
 created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX planning_illumination_due ON planning_illumination_work(status,next_attempt_at);
