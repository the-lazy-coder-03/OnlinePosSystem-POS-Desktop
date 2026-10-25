-- Additive LOCAL migration. Initial imports cannot overwrite existing data.
CREATE TABLE pos.catalogue_bootstrap (
  branch_id integer PRIMARY KEY REFERENCES pos.store_identity(branch_id),
  snapshot_id uuid NOT NULL UNIQUE,
  sha256 char(64) NOT NULL,
  snapshot jsonb NOT NULL,
  cutover_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
GRANT SELECT ON pos.catalogue_bootstrap TO pos_runtime;
-- Narrow one-shot write port retains the runtime prohibition on catalogue INSERT/DDL.
CREATE FUNCTION pos.bootstrap_catalogue(p_branch integer, p_id uuid, p_hash text,
    p_snapshot jsonb, p_items jsonb, p_prices jsonb) RETURNS void
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pos AS $$
BEGIN
  IF p_hash !~ '^[a-f0-9]{64}$' OR (p_snapshot->>'branchId')::integer <> p_branch
      OR (p_snapshot->>'snapshotId')::uuid <> p_id
      OR jsonb_array_length(p_items)=0 OR jsonb_array_length(p_prices)=0 THEN
    RAISE EXCEPTION 'Invalid bootstrap envelope' USING ERRCODE='23514';
  END IF;
  PERFORM position FROM pos.stream_head WHERE branch_id=p_branch FOR UPDATE;
  IF NOT FOUND THEN RAISE EXCEPTION 'Unknown branch'; END IF;
  IF EXISTS (SELECT 1 FROM pos.catalogue_bootstrap)
     OR EXISTS (SELECT 1 FROM pos.catalog_item)
     OR EXISTS (SELECT 1 FROM pos.branch_price)
     OR EXISTS (SELECT 1 FROM pos.order_draft) THEN
    RAISE EXCEPTION 'Bootstrap requires empty catalogue and orders' USING ERRCODE='23505';
  END IF;
  INSERT INTO pos.catalog_item(item_id,branch_id,source_type,source_id,description,definition,definition_revision)
    SELECT item_id,p_branch,source_type,source_id,description,definition,0
    FROM jsonb_to_recordset(p_items) AS x(item_id uuid,source_type text,source_id text,description text,definition jsonb);
  INSERT INTO pos.branch_price(price_id,branch_id,item_id,variant_key,amount,available,revision)
    SELECT price_id,p_branch,item_id,'baseline',amount,available,0
    FROM jsonb_to_recordset(p_prices) AS x(price_id uuid,item_id uuid,amount numeric,available boolean);
  INSERT INTO pos.catalogue_bootstrap(branch_id,snapshot_id,sha256,snapshot)
    VALUES(p_branch,p_id,p_hash,p_snapshot);
END $$;
REVOKE ALL ON FUNCTION pos.bootstrap_catalogue(integer,uuid,text,jsonb,jsonb,jsonb) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION pos.bootstrap_catalogue(integer,uuid,text,jsonb,jsonb,jsonb) TO pos_runtime;
