CREATE TABLE simulation_manifest_source (
 manifest_id text NOT NULL,
 plan_id text NOT NULL,
 PRIMARY KEY (manifest_id,plan_id)
);
CREATE INDEX simulation_manifest_plan ON simulation_manifest_source(plan_id,manifest_id);
-- Preserve links for manifests created before automatic source reconciliation was installed.
INSERT INTO simulation_manifest_source(manifest_id,plan_id)
SELECT h.id, e->>'planId' FROM state_head h,
 LATERAL jsonb_array_elements(h.body->'expected') e
WHERE h.kind='simulation-acquisition-manifest';
