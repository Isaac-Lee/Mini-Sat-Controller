-- Single-row control for orekit-data UTC/EOP collection; disabled until an ADMIN resumes it.
CREATE TABLE reference_archive_control (
  source text PRIMARY KEY,
  enabled boolean NOT NULL DEFAULT false,
  paused boolean NOT NULL DEFAULT false,
  next_attempt_at timestamptz NOT NULL DEFAULT now(),
  lease_token uuid,
  lease_until timestamptz,
  current_snapshot_id text,
  last_freshness text,
  last_error text,
  last_checked_at timestamptz
);
INSERT INTO reference_archive_control(source) VALUES('orekit-data');
