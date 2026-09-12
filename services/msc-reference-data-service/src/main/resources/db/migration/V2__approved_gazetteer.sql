CREATE TABLE gazetteer_alias (
  alias text NOT NULL,
  place_id text NOT NULL,
  version bigint NOT NULL CHECK (version > 0),
  PRIMARY KEY(alias,place_id)
);
CREATE INDEX gazetteer_alias_place ON gazetteer_alias(place_id);
