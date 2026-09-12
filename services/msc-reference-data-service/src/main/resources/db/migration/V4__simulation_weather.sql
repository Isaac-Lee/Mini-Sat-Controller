CREATE TABLE weather_coverage (
  snapshot_id TEXT PRIMARY KEY,
  issued_seconds BIGINT NOT NULL,
  issued_nanos INTEGER NOT NULL,
  start_seconds BIGINT NOT NULL,
  start_nanos INTEGER NOT NULL,
  end_seconds BIGINT NOT NULL,
  end_nanos INTEGER NOT NULL,
  west DOUBLE PRECISION NOT NULL,
  south DOUBLE PRECISION NOT NULL,
  east DOUBLE PRECISION NOT NULL,
  north DOUBLE PRECISION NOT NULL
);
CREATE INDEX weather_issued_order ON weather_coverage(issued_seconds DESC,issued_nanos DESC,snapshot_id);
