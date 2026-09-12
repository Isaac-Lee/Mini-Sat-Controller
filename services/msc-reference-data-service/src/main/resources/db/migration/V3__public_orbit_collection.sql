CREATE TABLE orbit_collection (
  norad_id integer PRIMARY KEY CHECK(norad_id BETWEEN 1 AND 999999999),
  display_name text NOT NULL,
  enabled boolean NOT NULL,
  next_attempt_at timestamptz NOT NULL DEFAULT now(),
  lease_token uuid,
  lease_until timestamptz,
  last_snapshot_id text,
  last_error text,
  fetched_at timestamptz
);
-- User-selected first target; collection remains explicitly enabled by registration API.
INSERT INTO orbit_collection(norad_id,display_name,enabled) VALUES(63229,'SPACEEYE-T1',false);

CREATE TABLE orbit_provider_control (
  provider text PRIMARY KEY,
  last_error text,
  lease_token uuid,
  lease_until timestamptz
);
INSERT INTO orbit_provider_control(provider) VALUES('CelesTrak');
