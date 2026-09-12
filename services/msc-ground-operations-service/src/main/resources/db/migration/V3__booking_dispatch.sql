CREATE TABLE booking_dispatch (
  booking_id text PRIMARY KEY,
  next_attempt_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  lease_until timestamptz,
  lease_token uuid,
  attempts integer NOT NULL DEFAULT 0,
  done boolean NOT NULL DEFAULT false
);
CREATE INDEX booking_dispatch_due ON booking_dispatch(next_attempt_at) WHERE NOT done;
