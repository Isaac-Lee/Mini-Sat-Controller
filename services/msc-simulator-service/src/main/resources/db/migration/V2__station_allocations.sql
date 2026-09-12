CREATE EXTENSION IF NOT EXISTS btree_gist;
CREATE TABLE station_allocation (
  booking_id text PRIMARY KEY,
  station_id text NOT NULL,
  start_tai numeric NOT NULL,
  end_tai numeric NOT NULL,
  active boolean NOT NULL DEFAULT true,
  CHECK(start_tai < end_tai),
  EXCLUDE USING gist(station_id WITH =, numrange(start_tai,end_tai,'[)') WITH &&) WHERE(active)
);
