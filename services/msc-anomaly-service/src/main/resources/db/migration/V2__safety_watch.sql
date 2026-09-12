CREATE TABLE safety_watch (
  spacecraft_id text PRIMARY KEY,
  next_check_at timestamptz NOT NULL DEFAULT now(),
  lease_until timestamptz,
  lease_token uuid
);
