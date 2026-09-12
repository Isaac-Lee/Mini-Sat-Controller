CREATE TABLE planning_inputs_epoch (id integer PRIMARY KEY CHECK(id=1),epoch bigint NOT NULL DEFAULT 0);
INSERT INTO planning_inputs_epoch(id) VALUES(1);
CREATE TABLE planning_work (
 request_id text PRIMARY KEY,
 revision bigint NOT NULL DEFAULT 0,
 invalidated_through bigint NOT NULL DEFAULT 0,
 priority integer NOT NULL DEFAULT 0,
 status text NOT NULL DEFAULT 'INVALIDATED',
 accepted jsonb,
 lease_token uuid,
 lease_until timestamptz,
 last_started_at timestamptz,
 next_attempt_at timestamptz NOT NULL DEFAULT now(),
 attempts bigint NOT NULL DEFAULT 0,
 captured_epoch bigint NOT NULL DEFAULT -1,
 last_attempt_id text,
 created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX planning_work_due ON planning_work(status,next_attempt_at,priority);
