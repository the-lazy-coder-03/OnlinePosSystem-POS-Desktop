CREATE FUNCTION pos.manage_local_store(proof text,terminal text,expected bigint,value jsonb) RETURNS bigint
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pos AS $$
DECLARE b integer;who uuid;version bigint;
BEGIN
 SELECT branch_id INTO b FROM pos.store_identity;
 PERFORM position FROM pos.stream_head WHERE branch_id=b FOR UPDATE;
 SELECT t.staff_id INTO who FROM pos.module_session t JOIN pos.staff s USING(branch_id,staff_id)
 JOIN pos.store_owner o ON o.branch_id=t.branch_id AND o.staff_id=t.staff_id
 WHERE t.branch_id=b AND t.token_hash=proof AND t.terminal_identity=terminal AND t.module='ADMINISTRATION'
 AND t.expires_at>clock_timestamp() AND s.enabled AND s.credential_version=t.credential_version;
 IF who IS NULL OR NOT EXISTS(SELECT 1 FROM pos.admin_permissions(b,who) p WHERE p='module.administration')
 THEN RAISE EXCEPTION 'Designated local Owner required' USING ERRCODE='42501'; END IF;
 SELECT revision INTO version FROM pos.store_identity;
 IF version<>expected THEN RAISE EXCEPTION 'Store changed; reload' USING ERRCODE='40001'; END IF;
 IF jsonb_typeof(value)<>'object' OR value-ARRAY['businessName','name','code','address','phone','email','active']<>'{}'::jsonb
 OR value->>'name' IS NULL OR length(trim(value->>'name')) NOT BETWEEN 1 AND 200
 OR value->>'businessName' IS NULL OR length(trim(value->>'businessName')) NOT BETWEEN 1 AND 200
 OR value->>'code' IS NULL OR length(trim(value->>'code')) NOT BETWEEN 1 AND 40
 OR COALESCE(length(value->>'address'),0)>2000 OR COALESCE(length(value->>'phone'),0)>64 OR COALESCE(length(value->>'email'),0)>254
 OR NOT (value ? 'active') OR jsonb_typeof(value->'active')<>'boolean' THEN RAISE EXCEPTION 'Invalid store details' USING ERRCODE='23514'; END IF;
 UPDATE pos.store_identity SET business_name=trim(value->>'businessName'),store_name=trim(value->>'name'),store_code=trim(value->>'code'),
 address=COALESCE(value->>'address',''),phone=COALESCE(value->>'phone',''),email=COALESCE(value->>'email',''),
 active=(value->>'active')::boolean,revision=revision+1 RETURNING revision INTO version;
 RETURN version;
END $$;
REVOKE ALL ON FUNCTION pos.manage_local_store(text,text,bigint,jsonb) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION pos.manage_local_store(text,text,bigint,jsonb) TO pos_runtime;
