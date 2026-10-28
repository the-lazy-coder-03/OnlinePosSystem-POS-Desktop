-- Catalogue definitions stay in their existing JSON records. Historical rows remain unchanged.
ALTER TABLE pos.catalogue_record ADD CONSTRAINT category_archive_state CHECK (
  source_type NOT IN ('menu_category','pizza_category') OR NOT (data ? 'archived') OR (
    jsonb_typeof(data->'archived')='boolean' AND
    (NOT (data->>'archived')::boolean OR NOT (data->>'active')::boolean)
  )
);
