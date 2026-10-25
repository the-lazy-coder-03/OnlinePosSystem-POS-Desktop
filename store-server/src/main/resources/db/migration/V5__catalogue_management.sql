-- Local editable working records. The immutable bootstrap snapshot remains untouched.
CREATE TABLE pos.catalogue_version (
 branch_id integer PRIMARY KEY REFERENCES pos.catalogue_bootstrap(branch_id),
 revision bigint NOT NULL DEFAULT 0 CHECK(revision>=0)
);
CREATE TABLE pos.catalogue_record (
 branch_id integer NOT NULL REFERENCES pos.catalogue_version(branch_id),
 source_type varchar(40) NOT NULL,
 source_id varchar(128) NOT NULL,
 data jsonb NOT NULL CHECK(jsonb_typeof(data)='object'),
 PRIMARY KEY(branch_id,source_type,source_id)
);
CREATE SEQUENCE pos.catalogue_local_id START WITH 1000000000 MAXVALUE 2147483647;
GRANT USAGE ON SEQUENCE pos.catalogue_local_id TO pos_runtime;
GRANT SELECT ON pos.catalogue_version,pos.catalogue_record TO pos_runtime;
CREATE FUNCTION pos.initialize_catalogue_records(p_branch integer,p_tables jsonb) RETURNS void
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pos AS $$
DECLARE t record; r jsonb;
BEGIN
 INSERT INTO pos.catalogue_version(branch_id) VALUES(p_branch);
 -- Source keys are retained exactly as imported by CatalogueImport/SnapshotFormat.
 FOR t IN SELECT key,value FROM jsonb_each(p_tables) LOOP
  FOR r IN SELECT value FROM jsonb_array_elements(t.value) LOOP
   INSERT INTO pos.catalogue_record VALUES(p_branch,t.key,
    replace((CASE t.key
     WHEN 'branch' THEN jsonb_build_array(r->'branch_id')
     WHEN 'branch_menu_item_price' THEN jsonb_build_array(r->'branch_id',r->'menu_item_id')
     WHEN 'menu_item_modifier_group' THEN jsonb_build_array(r->'menu_item_id',r->'group_id')
     WHEN 'burger_component' THEN jsonb_build_array(r->'component_id')
     WHEN 'burger_recipe' THEN jsonb_build_array(r->'recipe_id')
     WHEN 'burger_recipe_component' THEN jsonb_build_array(r->'recipe_id',r->'component_id')
     WHEN 'burger_recipe_assignment' THEN jsonb_build_array(r->'burger_id')
     WHEN 'burger_item_default_component' THEN jsonb_build_array(r->'burger_id',r->'component_id')
     WHEN 'branch_burger_component_price' THEN jsonb_build_array(r->'branch_id',r->'component_id')
     WHEN 'pizza_category' THEN jsonb_build_array(r->'pizza_category_id')
     WHEN 'pizza' THEN jsonb_build_array(r->'pizza_id')
     WHEN 'pizza_size' THEN jsonb_build_array(r->'pizza_size_id')
     WHEN 'pizza_allowed_size' THEN jsonb_build_array(r->'pizza_id',r->'pizza_size_id')
     WHEN 'price_category' THEN jsonb_build_array(r->'price_category_id')
     WHEN 'ingredient' THEN jsonb_build_array(r->'ingredient_id')
     WHEN 'pizza_default_ingredient' THEN jsonb_build_array(r->'pizza_id',r->'ingredient_id')
     WHEN 'branch_pizza_price' THEN jsonb_build_array(r->'branch_id',r->'pizza_id',r->'pizza_size_id')
     WHEN 'branch_extra_price' THEN jsonb_build_array(r->'branch_id',r->'price_category_id',r->'pizza_size_id')
     WHEN 'pizza_base_option' THEN jsonb_build_array(r->'pizza_base_option_id')
     WHEN 'branch_pizza_base_option_price' THEN jsonb_build_array(r->'branch_id',r->'pizza_base_option_id',r->'pizza_size_id')
     WHEN 'special' THEN jsonb_build_array(r->'special_id')
     WHEN 'special_day' THEN jsonb_build_array(r->'special_id',r->'day_of_week')
     WHEN 'special_component' THEN jsonb_build_array(r->'special_component_id')
     WHEN 'special_component_menu_item' THEN jsonb_build_array(r->'special_component_id',r->'menu_item_id')
     WHEN 'special_component_pizza' THEN jsonb_build_array(r->'special_component_id',r->'pizza_id')
     WHEN 'special_addon' THEN jsonb_build_array(r->'special_addon_id')
     ELSE jsonb_build_array(r->'id') END)::text,' ','') ,r);
  END LOOP;
 END LOOP;
 RETURN;
END $$;
CREATE FUNCTION pos.seed_catalogue_records() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pos AS $$
BEGIN
 PERFORM pos.initialize_catalogue_records(NEW.branch_id,NEW.snapshot->'tables');
 RETURN NULL;
END $$;
CREATE TRIGGER seed_catalogue_records AFTER INSERT ON pos.catalogue_bootstrap
 FOR EACH ROW EXECUTE FUNCTION pos.seed_catalogue_records();
-- Initialize existing stores without issuing any UPDATE to their immutable import.
DO $$ DECLARE b record; BEGIN
 FOR b IN SELECT branch_id,snapshot FROM pos.catalogue_bootstrap LOOP
  PERFORM pos.initialize_catalogue_records(b.branch_id,b.snapshot->'tables');
 END LOOP;
END $$;
-- Extend only the implemented action allowlist; no default role receives editing rights.
DO $$ DECLARE definition text; BEGIN
 SELECT pg_get_functiondef('pos.apply_admin_change(text,text,text,jsonb)'::regprocedure) INTO definition;
 definition=replace(definition,'''staff.manage'',''rbac.manage'',''audit.view''','''staff.manage'',''rbac.manage'',''audit.view'',''catalogue.manage''');
 EXECUTE definition;
END $$;
CREATE FUNCTION pos.apply_catalogue_change(proof text,terminal text,expected bigint,records jsonb,prices jsonb) RETURNS bigint
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pos AS $$
DECLARE b integer; who uuid; version bigint;
BEGIN
 SELECT branch_id INTO b FROM pos.store_identity;
 PERFORM position FROM pos.stream_head WHERE branch_id=b FOR UPDATE;
 SELECT t.staff_id INTO who FROM pos.module_session t JOIN pos.staff s USING(branch_id,staff_id)
 WHERE t.branch_id=b AND t.token_hash=proof AND t.terminal_identity=terminal AND t.module='CATALOGUE'
 AND t.expires_at>clock_timestamp() AND s.enabled AND s.credential_version=t.credential_version;
 IF who IS NULL THEN RAISE EXCEPTION 'Catalogue session required' USING ERRCODE='28000'; END IF;
 IF NOT EXISTS(SELECT 1 FROM pos.admin_permissions(b,who) p WHERE p='module.catalogue') OR
 NOT EXISTS(SELECT 1 FROM pos.admin_permissions(b,who) p WHERE p='catalogue.manage') THEN
  RAISE EXCEPTION 'Catalogue edit permission required' USING ERRCODE='42501';
 END IF;
 SELECT revision INTO version FROM pos.catalogue_version WHERE branch_id=b FOR UPDATE;
 IF version IS NULL OR version<>expected THEN RAISE EXCEPTION 'Catalogue changed; reload' USING ERRCODE='23514'; END IF;
 -- Protect immutable source families and prevent destructive master removal at the write port.
 IF EXISTS(SELECT 1 FROM pos.catalogue_record r WHERE r.branch_id=b
 AND (r.source_type='branch' OR r.source_type LIKE 'special%') AND NOT EXISTS(
 SELECT 1 FROM jsonb_to_recordset(records) x(source_type text,source_id text,data jsonb)
 WHERE x.source_type=r.source_type AND x.source_id::jsonb=r.source_id::jsonb AND x.data=r.data)) THEN
  RAISE EXCEPTION 'Immutable catalogue family' USING ERRCODE='42501';
 END IF;
 IF EXISTS(SELECT 1 FROM pos.catalogue_record r WHERE r.branch_id=b
 AND r.source_type IN ('menu_item','pizza','menu_category','pizza_category','pizza_size','price_category','ingredient','burger_component','burger_recipe','modifier_group','pizza_base_option')
 AND NOT EXISTS(SELECT 1 FROM jsonb_to_recordset(records) x(source_type text,source_id text) WHERE x.source_type=r.source_type AND x.source_id::jsonb=r.source_id::jsonb)) THEN
  RAISE EXCEPTION 'Deactivate master records instead' USING ERRCODE='23514';
 END IF;
 -- Preserve an existing working key's textual representation. Numeric JSON equality
 -- also handles Java's scientific notation without creating duplicate logical records.
 INSERT INTO pos.catalogue_record SELECT b,x.source_type,coalesce(r.source_id,x.source_id),x.data
 FROM jsonb_to_recordset(records) x(source_type text,source_id text,data jsonb)
 LEFT JOIN pos.catalogue_record r ON r.branch_id=b AND r.source_type=x.source_type AND r.source_id::jsonb=x.source_id::jsonb
 ON CONFLICT(branch_id,source_type,source_id) DO UPDATE SET data=excluded.data;
 DELETE FROM pos.catalogue_record r WHERE r.branch_id=b AND NOT EXISTS(
 SELECT 1 FROM jsonb_to_recordset(records) x(source_type text,source_id text) WHERE x.source_type=r.source_type AND x.source_id::jsonb=r.source_id::jsonb);
 INSERT INTO pos.catalog_item(item_id,branch_id,source_type,source_id,description,definition,definition_revision)
 SELECT x.item_id,b,x.source_type,x.source_id,x.description,x.definition,version+1
 FROM jsonb_to_recordset(prices) x(item_id uuid,source_type text,source_id text,description text,definition jsonb)
 ON CONFLICT(item_id) DO UPDATE SET description=excluded.description,definition=excluded.definition,definition_revision=excluded.definition_revision;
 UPDATE pos.branch_price p SET available=false,revision=revision+1 WHERE p.branch_id=b AND p.available AND NOT EXISTS(
 SELECT 1 FROM jsonb_to_recordset(prices) x(price_id uuid) WHERE x.price_id=p.price_id);
 INSERT INTO pos.branch_price(price_id,branch_id,item_id,variant_key,amount,available,revision)
 SELECT x.price_id,b,x.item_id,'baseline',x.amount,x.available,version+1
 FROM jsonb_to_recordset(prices) x(price_id uuid,item_id uuid,amount numeric,available boolean)
 ON CONFLICT(price_id) DO UPDATE SET amount=excluded.amount,available=excluded.available,revision=pos.branch_price.revision+1;
 UPDATE pos.catalogue_version SET revision=revision+1 WHERE branch_id=b RETURNING revision INTO version;
 RETURN version;
END $$;
REVOKE ALL ON FUNCTION pos.seed_catalogue_records() FROM PUBLIC;
REVOKE ALL ON FUNCTION pos.apply_catalogue_change(text,text,bigint,jsonb,jsonb) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION pos.apply_catalogue_change(text,text,bigint,jsonb,jsonb) TO pos_runtime;

REVOKE ALL ON FUNCTION pos.initialize_catalogue_records(integer,jsonb) FROM PUBLIC;
