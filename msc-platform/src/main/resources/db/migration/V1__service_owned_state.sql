CREATE TABLE state_head (
  kind text NOT NULL, id text NOT NULL, version bigint NOT NULL CHECK (version > 0),
  body jsonb NOT NULL, PRIMARY KEY (kind,id)
);
CREATE TABLE state_history (
  kind text NOT NULL, id text NOT NULL, version bigint NOT NULL CHECK (version > 0),
  body jsonb NOT NULL, PRIMARY KEY (kind,id,version)
);
CREATE TABLE outbox (
  event_id uuid PRIMARY KEY, event_type text NOT NULL, envelope jsonb NOT NULL,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(), published_at timestamptz,
  attempts integer NOT NULL DEFAULT 0, last_error text
);
CREATE INDEX outbox_pending ON outbox(created_at) WHERE published_at IS NULL;
CREATE TABLE inbox (
  event_id uuid PRIMARY KEY, received_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE idempotency (
  scope text NOT NULL, request_key text NOT NULL, fingerprint text NOT NULL,
  response jsonb NOT NULL, PRIMARY KEY(scope,request_key)
);
