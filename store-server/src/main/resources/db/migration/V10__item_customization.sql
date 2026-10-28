-- Additive local customization policy; source catalogue and historical drafts remain intact.
CREATE TABLE pos.item_customization (
 branch_id integer PRIMARY KEY REFERENCES pos.catalogue_version(branch_id),
 rules jsonb NOT NULL CHECK(jsonb_typeof(rules)='object'),updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),updated_by uuid NOT NULL
);
GRANT SELECT ON pos.item_customization TO pos_runtime;
CREATE FUNCTION pos.apply_item_customization(proof text,terminal text,expected bigint,value jsonb) RETURNS bigint
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pos AS $$
DECLARE b integer;who uuid;version bigint;
BEGIN
 SELECT branch_id INTO b FROM pos.store_identity;PERFORM position FROM pos.stream_head WHERE branch_id=b FOR UPDATE;
 SELECT t.staff_id INTO who FROM pos.module_session t JOIN pos.staff s USING(branch_id,staff_id)
 WHERE t.branch_id=b AND t.token_hash=proof AND t.terminal_identity=terminal AND t.module='CATALOGUE'
 AND t.expires_at>clock_timestamp() AND s.enabled AND s.credential_version=t.credential_version;
 IF who IS NULL THEN RAISE EXCEPTION 'Catalogue session required' USING ERRCODE='28000'; END IF;
 IF NOT EXISTS(SELECT 1 FROM pos.admin_permissions(b,who) p WHERE p='module.catalogue') OR NOT EXISTS(SELECT 1 FROM pos.admin_permissions(b,who) p WHERE p='catalogue.manage') THEN RAISE EXCEPTION 'Catalogue edit permission required' USING ERRCODE='42501'; END IF;
 SELECT revision INTO version FROM pos.catalogue_version WHERE branch_id=b FOR UPDATE;
 IF version IS NULL OR version<>expected THEN RAISE EXCEPTION 'Catalogue changed; reload' USING ERRCODE='23514'; END IF;
 IF jsonb_typeof(value)<>'object' OR (SELECT count(*) FROM jsonb_object_keys(value))<>4 OR NOT value ?& ARRAY['categoryExtras','swapGroups','items','combos'] THEN RAISE EXCEPTION 'Invalid customization rules' USING ERRCODE='23514'; END IF;
 INSERT INTO pos.item_customization VALUES(b,value,clock_timestamp(),who) ON CONFLICT(branch_id) DO UPDATE SET rules=value,updated_at=excluded.updated_at,updated_by=who;
 UPDATE pos.catalogue_version SET revision=revision+1 WHERE branch_id=b RETURNING revision INTO version;RETURN version;
END $$;
REVOKE ALL ON FUNCTION pos.apply_item_customization(text,text,bigint,jsonb) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION pos.apply_item_customization(text,text,bigint,jsonb) TO pos_runtime;
