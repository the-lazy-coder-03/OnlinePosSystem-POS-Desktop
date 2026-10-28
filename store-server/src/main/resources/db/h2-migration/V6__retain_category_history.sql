-- Preserve actual pre-grid category history. Do not fabricate old profile snapshots.
INSERT INTO dev_meta.module_events(event_id,command_id,action,entity_id,revision,payload,recorded_at)
SELECT c.change_id,c.command_id,'CATALOGUE_CHANGED',NULL,c.revision,
'{"historicalCategoryId":' || c.category_id || '}',c.recorded_at
FROM dev_meta.changes c WHERE NOT EXISTS(SELECT 1 FROM dev_meta.module_events e WHERE e.command_id=c.command_id);
